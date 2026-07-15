package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomResponse;
import shinhan.fibri.ieum.main.friend.service.FriendRequestNotifier;
import shinhan.fibri.ieum.main.friend.service.FriendService;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ChatService.class, ChatRoomLifecycleService.class, FriendService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuestionRoomConcurrencyIntegrationTest {

	private static final String DATABASE = "ieum_question_room_concurrency";
	private static final int ADVISORY_LOCK_NAMESPACE = 68;
	private static final int ADVISORY_LOCK_KEY = 1;

	static {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
	}

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> CanonicalPostgresContainer.jdbcUrl(DATABASE));
		registry.add("spring.datasource.username", CanonicalPostgresContainer::username);
		registry.add("spring.datasource.password", CanonicalPostgresContainer::password);
		registry.add("spring.datasource.driver-class-name", CanonicalPostgresContainer::driverClassName);
		registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
		registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
		registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
		registry.add(
			"spring.jpa.properties.hibernate.dialect",
			() -> "shinhan.fibri.ieum.common.config.SnakeCasePostgreSQLDialect"
		);
	}

	@Autowired
	private ChatService service;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private DataSource dataSource;

	@MockitoBean
	private FriendRequestNotifier friendRequestNotifier;

	@MockitoBean
	private ChatRoomSummaryQueryService chatRoomSummaryQueryService;

	@MockitoBean
	private ChatRoomListChangeEmitter chatRoomListChangeEmitter;

	private long ownerId;
	private long answererId;
	private long questionId;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcTemplate admin = new JdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"));
		admin.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
	}

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
		ownerId = insertUser("concurrent-owner");
		answererId = insertUser("concurrent-answerer");
		questionId = insertQuestion(ownerId);
		jdbc.update("""
			INSERT INTO answers (question_id, author_id, is_ai, content)
			VALUES (?, ?, false, 'human answer')
			""", questionId, answererId);
		installQuestionRoomInsertGate();
	}

	@Test
	void concurrentSameTupleRequestsConvergeOnOneRoom() throws Exception {
		try (Connection gate = dataSource.getConnection();
			 ExecutorService executor = Executors.newFixedThreadPool(2)) {
			lockInsertGate(gate);

			Future<ChatRoomResponse> first = executor.submit(this::createQuestionRoom);
			awaitBlockedRoomInserts(1);
			Future<ChatRoomResponse> second = executor.submit(this::createQuestionRoom);

			try {
				awaitBlockedRoomInserts(2);
			} finally {
				gate.commit();
			}

			ChatRoomResponse firstResponse = first.get(10, TimeUnit.SECONDS);
			ChatRoomResponse secondResponse = second.get(10, TimeUnit.SECONDS);
			assertThat(firstResponse.roomId()).isEqualTo(secondResponse.roomId());
			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM chat_rooms WHERE room_key = ?",
				Integer.class,
				questionRoomKey()
			)).isOne();
			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM chat_members WHERE room_id = ?",
				Integer.class,
				firstResponse.roomId()
			)).isEqualTo(2);
		}
	}

	@Test
	void questionDeletionWaitsUntilRoomCreationCommits() throws Exception {
		try (Connection gate = dataSource.getConnection();
			 ExecutorService executor = Executors.newFixedThreadPool(2)) {
			lockInsertGate(gate);

			Future<ChatRoomResponse> roomCreation = executor.submit(this::createQuestionRoom);
			awaitBlockedRoomInserts(1);
			Future<Integer> deletion = executor.submit(() -> jdbc.update(
				"UPDATE questions SET deleted_at = now() WHERE question_id = ?",
				questionId
			));

			try {
				assertThatThrownBy(() -> deletion.get(500, TimeUnit.MILLISECONDS))
					.isInstanceOf(TimeoutException.class);
			} finally {
				gate.commit();
			}

			ChatRoomResponse response = roomCreation.get(10, TimeUnit.SECONDS);
			assertThat(deletion.get(10, TimeUnit.SECONDS)).isOne();
			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM chat_rooms WHERE room_id = ?",
				Integer.class,
				response.roomId()
			)).isOne();
			assertThat(jdbc.queryForObject(
				"SELECT deleted_at IS NOT NULL FROM questions WHERE question_id = ?",
				Boolean.class,
				questionId
			)).isTrue();
		}
	}

	private ChatRoomResponse createQuestionRoom() {
		return service.createQuestionRoom(principal(ownerId), questionId, answererId);
	}

	private void installQuestionRoomInsertGate() {
		jdbc.execute("DROP TRIGGER IF EXISTS block_question_room_insert_for_test ON chat_rooms");
		jdbc.execute("""
			CREATE OR REPLACE FUNCTION block_question_room_insert_for_test()
			RETURNS trigger
			LANGUAGE plpgsql
			AS $function$
			BEGIN
				PERFORM pg_advisory_xact_lock(68, 1);
				RETURN NEW;
			END;
			$function$
			""");
		jdbc.execute("""
			CREATE TRIGGER block_question_room_insert_for_test
			BEFORE INSERT ON chat_rooms
			FOR EACH ROW
			WHEN (NEW.room_type = 'question')
			EXECUTE FUNCTION block_question_room_insert_for_test()
			""");
	}

	private void lockInsertGate(Connection gate) {
		try {
			gate.setAutoCommit(false);
			new JdbcTemplate(new SingleConnectionDataSource(gate, true))
				.queryForObject(
					"SELECT pg_advisory_xact_lock(?, ?)",
					Object.class,
					ADVISORY_LOCK_NAMESPACE,
					ADVISORY_LOCK_KEY
				);
		} catch (java.sql.SQLException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private void awaitBlockedRoomInserts(int expectedCount) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
		while (System.nanoTime() < deadline) {
			Integer waiting = jdbc.queryForObject("""
				SELECT count(*)
				  FROM pg_locks
				 WHERE locktype = 'advisory'
				   AND classid = ?
				   AND objid = ?
				   AND NOT granted
				""", Integer.class, ADVISORY_LOCK_NAMESPACE, ADVISORY_LOCK_KEY);
			if (waiting != null && waiting >= expectedCount) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError(expectedCount + " question-room inserts did not reach the advisory-lock gate");
	}

	private long insertUser(String nickname) {
		return jdbc.queryForObject("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (?, 'hash', ?, true)
			RETURNING user_id
			""", Long.class, nickname + "@example.com", nickname);
	}

	private long insertQuestion(long authorId) {
		long pinId = jdbc.queryForObject("""
			INSERT INTO pins (author_id, pin_type, location, address)
			VALUES (?, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, '서울특별시')
			RETURNING pin_id
			""", Long.class, authorId);
		return jdbc.queryForObject("""
			INSERT INTO questions (pin_id, author_id, title, content)
			VALUES (?, ?, 'title', 'content')
			RETURNING question_id
			""", Long.class, pinId, authorId);
	}

	private String questionRoomKey() {
		return "q:%d:%d:%d".formatted(questionId, ownerId, answererId);
	}

	private AuthenticatedUser principal(long userId) {
		return new AuthenticatedUser(userId, "owner@example.com", UserRole.user, UserStatus.active);
	}
}
