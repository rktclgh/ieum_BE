package shinhan.fibri.ieum.main.answer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AnswerRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_main_answer_repository";

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
	private AnswerRepository repository;

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
		long ownerId = insertUser("owner");
		long firstAnswererId = insertUser("first-answerer");
		long secondAnswererId = insertUser("second-answerer");
		long firstQuestionId = insertQuestion(ownerId, "first question");
		long secondQuestionId = insertQuestion(ownerId, "second question");
		insertHumanAnswer(firstQuestionId, firstAnswererId);
		insertHumanAnswer(secondQuestionId, secondAnswererId);
		insertAiAnswer(firstQuestionId);
	}

	@Test
	void humanAnswerPredicateMatchesOnlyExactQuestionAndAuthorAndExcludesAi() {
		assertThat(repository.existsByQuestionIdAndAuthorIdAndAiFalse(1L, 2L)).isTrue();
		assertThat(repository.existsByQuestionIdAndAuthorIdAndAiFalse(2L, 2L)).isFalse();
		assertThat(repository.existsByQuestionIdAndAuthorIdAndAiFalse(1L, 3L)).isFalse();
		assertThat(repository.existsByQuestionIdAndAuthorIdAndAiFalse(1L, null)).isFalse();
	}

	private long insertUser(String nickname) {
		return jdbc.queryForObject("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (?, 'hash', ?, true)
			RETURNING user_id
			""", Long.class, nickname + "@example.com", nickname);
	}

	private long insertQuestion(long ownerId, String title) {
		long pinId = jdbc.queryForObject("""
			INSERT INTO pins (author_id, pin_type, location, address)
			VALUES (?, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, '서울특별시')
			RETURNING pin_id
			""", Long.class, ownerId);
		return jdbc.queryForObject("""
			INSERT INTO questions (pin_id, author_id, title, content)
			VALUES (?, ?, ?, 'content')
			RETURNING question_id
			""", Long.class, pinId, ownerId, title);
	}

	private void insertHumanAnswer(long questionId, long authorId) {
		jdbc.update("""
			INSERT INTO answers (question_id, author_id, is_ai, content)
			VALUES (?, ?, false, 'human answer')
			""", questionId, authorId);
	}

	private void insertAiAnswer(long questionId) {
		jdbc.update("""
			INSERT INTO answers (question_id, author_id, is_ai, content)
			VALUES (?, NULL, true, 'ai answer')
			""", questionId);
	}
}
