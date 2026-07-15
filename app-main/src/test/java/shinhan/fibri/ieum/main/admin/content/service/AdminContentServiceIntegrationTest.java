package shinhan.fibri.ieum.main.admin.content.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
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
import shinhan.fibri.ieum.main.ai.question.repository.JdbcQuestionAnswerTicketWriter;
import shinhan.fibri.ieum.main.pin.repository.JdbcPinWriter;
import shinhan.fibri.ieum.main.question.service.QuestionDeletionExecutor;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AdminContentService.class, QuestionDeletionExecutor.class, JdbcPinWriter.class, JdbcQuestionAnswerTicketWriter.class})
class AdminContentServiceIntegrationTest {

	private static final String DATABASE = "ieum_admin_content_service";

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
	private AdminContentService service;

	@Autowired
	private JdbcTemplate jdbc;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcTemplate admin = new JdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"));
		admin.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
	}

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
	}

	@Test
	void hideQuestionMarksQuestionPinAndAiTicketInOneServiceCall() {
		long userId = insertUser();
		long pinId = insertPin(userId);
		long questionId = insertQuestion(userId, pinId);
		jdbc.update("INSERT INTO ai_question_tasks (question_id) VALUES (?)", questionId);

		service.hide("question", questionId);

		OffsetDateTime questionDeletedAt = jdbc.queryForObject(
			"SELECT deleted_at FROM questions WHERE question_id = ?",
			OffsetDateTime.class,
			questionId
		);
		OffsetDateTime pinDeletedAt = jdbc.queryForObject(
			"SELECT deleted_at FROM pins WHERE pin_id = ?",
			OffsetDateTime.class,
			pinId
		);
		OffsetDateTime cancelRequestedAt = jdbc.queryForObject(
			"SELECT cancel_requested_at FROM ai_question_tasks WHERE question_id = ?",
			OffsetDateTime.class,
			questionId
		);

		assertThat(questionDeletedAt).isNotNull();
		assertThat(pinDeletedAt).isEqualTo(questionDeletedAt);
		assertThat(cancelRequestedAt).isNotNull();
	}

	private long insertUser() {
		return jdbc.queryForObject(
			"""
				INSERT INTO users (email, password_hash, nickname, email_verified)
				VALUES ('admin-content-owner@example.com', 'hash', 'owner', true)
				RETURNING user_id
				""",
			Long.class
		);
	}

	private long insertPin(long userId) {
		return jdbc.queryForObject(
			"""
				INSERT INTO pins (author_id, pin_type, location, address)
				VALUES (?, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, 'Seoul')
				RETURNING pin_id
				""",
			Long.class,
			userId
		);
	}

	private long insertQuestion(long userId, long pinId) {
		return jdbc.queryForObject(
			"""
				INSERT INTO questions (pin_id, author_id, title, content)
				VALUES (?, ?, 'question', 'content')
				RETURNING question_id
				""",
			Long.class,
			pinId,
			userId
		);
	}
}
