package shinhan.fibri.ieum.main.answer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
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
import shinhan.fibri.ieum.main.answer.dto.CreateAnswerRequest;
import shinhan.fibri.ieum.main.answer.dto.FinalizeAcceptedAnswersRequest;
import shinhan.fibri.ieum.main.answer.exception.AnswerSelectionFinalizedException;
import shinhan.fibri.ieum.main.notification.service.NotificationPublisher;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AnswerService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnswerFinalizationConcurrencyIntegrationTest {

	private static final String DATABASE = "ieum_answer_finalization_concurrency";
	private static final int ADVISORY_LOCK_NAMESPACE = 83;
	private static final int ADVISORY_LOCK_KEY = 84;

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
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
	}

	@Autowired
	private AnswerService service;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private DataSource dataSource;

	@MockitoBean
	private NotificationPublisher notificationPublisher;

	private long ownerId;
	private long answererId;
	private long questionId;
	private long acceptedAnswerId;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcTemplate admin = new JdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"));
		admin.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
	}

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
		ownerId = insertUser("finalization-owner");
		answererId = insertUser("finalization-answerer");
		questionId = insertQuestion(ownerId);
		acceptedAnswerId = insertHumanAnswer(questionId, answererId, "accepted answer");
		jdbc.execute("DROP TRIGGER IF EXISTS block_answer_insert_for_finalization_test ON answers");
		jdbc.execute("""
			CREATE OR REPLACE FUNCTION block_answer_insert_for_finalization_test()
			RETURNS trigger
			LANGUAGE plpgsql
			AS $function$
			BEGIN
				PERFORM pg_advisory_xact_lock(83, 84);
				RETURN NEW;
			END;
			$function$
			""");
		jdbc.execute("""
			CREATE TRIGGER block_answer_insert_for_finalization_test
			BEFORE INSERT ON answers
			FOR EACH ROW
			EXECUTE FUNCTION block_answer_insert_for_finalization_test()
			""");
	}

	@Test
	void finalizationWaitsForInFlightAnswerCreateAndLaterCreateIsRejectedWithoutInsert() throws Exception {
		try (Connection gate = dataSource.getConnection();
			 ExecutorService executor = Executors.newFixedThreadPool(2)) {
			gate.setAutoCommit(false);
			new JdbcTemplate(new SingleConnectionDataSource(gate, true))
				.queryForObject("SELECT pg_advisory_xact_lock(?, ?)", Object.class,
					ADVISORY_LOCK_NAMESPACE, ADVISORY_LOCK_KEY);

			Future<?> inFlightCreate = executor.submit(() -> service.create(
				principal(answererId, "finalization-answerer@example.com"),
				questionId,
				new CreateAnswerRequest("in-flight answer", List.of())
			));
			awaitBlockedAnswerInserts(1);

			Future<?> finalization = executor.submit(() -> service.finalizeSelection(
				principal(ownerId, "finalization-owner@example.com"),
				questionId,
				new FinalizeAcceptedAnswersRequest(List.of(acceptedAnswerId))
			));

			try {
				assertThatThrownBy(() -> finalization.get(500, TimeUnit.MILLISECONDS))
					.isInstanceOf(TimeoutException.class);
			} finally {
				gate.commit();
			}

			inFlightCreate.get(5, TimeUnit.SECONDS);
			finalization.get(5, TimeUnit.SECONDS);
		}

		assertThat(jdbc.queryForObject(
			"SELECT is_resolved FROM questions WHERE question_id = ?",
			Boolean.class,
			questionId
		)).isTrue();
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM answers WHERE question_id = ?",
			Integer.class,
			questionId
		)).isEqualTo(2);

		assertThatThrownBy(() -> service.create(
			principal(answererId, "finalization-answerer@example.com"),
			questionId,
			new CreateAnswerRequest("late answer", List.of())
		)).isInstanceOf(AnswerSelectionFinalizedException.class);
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM answers WHERE question_id = ?",
			Integer.class,
			questionId
		)).isEqualTo(2);
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

	private long insertHumanAnswer(long targetQuestionId, long authorId, String content) {
		return jdbc.queryForObject("""
			INSERT INTO answers (question_id, author_id, is_ai, content)
			VALUES (?, ?, false, ?)
			RETURNING answer_id
			""", Long.class, targetQuestionId, authorId, content);
	}

	private AuthenticatedUser principal(long userId, String email) {
		return new AuthenticatedUser(userId, email, UserRole.user, UserStatus.active);
	}

	private void awaitBlockedAnswerInserts(int expectedCount) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
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
		throw new AssertionError(expectedCount + " answer inserts did not reach the advisory-lock gate");
	}
}
