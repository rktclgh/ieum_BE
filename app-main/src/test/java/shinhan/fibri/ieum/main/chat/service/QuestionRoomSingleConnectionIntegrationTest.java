package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
class QuestionRoomSingleConnectionIntegrationTest {

	private static final String DATABASE = "ieum_question_room_single_connection";

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
		registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
		registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
		registry.add("spring.datasource.hikari.connection-timeout", () -> "250");
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
		ownerId = insertUser("question-owner");
		answererId = insertUser("question-answerer");
		questionId = insertQuestion(ownerId);
		jdbc.update("""
			INSERT INTO answers (question_id, author_id, is_ai, content)
			VALUES (?, ?, false, 'human answer')
			""", questionId, answererId);
	}

	@Test
	void createsQuestionRoomWithoutRequestingASecondConnection() throws Exception {
		assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()).isOne();

		ChatRoomResponse response = assertTimeout(
			Duration.ofSeconds(3),
			() -> service.createQuestionRoom(principal(ownerId), questionId, answererId)
		);

		assertThat(response.questionId()).isEqualTo(questionId);
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM chat_rooms WHERE room_key = ?",
			Integer.class,
			"q:%d:%d:%d".formatted(questionId, ownerId, answererId)
		)).isOne();
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM chat_members WHERE room_id = ?",
			Integer.class,
			response.roomId()
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

	private AuthenticatedUser principal(long userId) {
		return new AuthenticatedUser(userId, "owner@example.com", UserRole.user, UserStatus.active);
	}
}
