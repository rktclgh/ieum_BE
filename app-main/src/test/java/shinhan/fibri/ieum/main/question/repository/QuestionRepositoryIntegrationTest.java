package shinhan.fibri.ieum.main.question.repository;

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
class QuestionRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_main_question_repository";

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
	private QuestionRepository repository;

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
		long userId = jdbc.queryForObject("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES ('question-owner@example.com', 'hash', 'question-owner', true)
			RETURNING user_id
			""", Long.class);
		insertQuestion(userId, "active question", null);
		insertQuestion(userId, "deleted question", "2026-07-13T10:00:00Z");
	}

	@Test
	void detailExcludesSoftDeletedQuestion() {
		assertThat(repository.findDetailByQuestionId(1L)).isPresent();
		assertThat(repository.findDetailByQuestionId(2L)).isEmpty();
	}

	@Test
	void mineExcludesSoftDeletedQuestion() {
		assertThat(repository.findMineFirstPage(1L, 10))
			.extracting(MyQuestionItemProjection::getTitle)
			.containsExactly("active question");
	}

	@Test
	void entityQueriesExcludeSoftDeletedQuestion() {
		assertThat(repository.existsById(2L)).isFalse();
		assertThat(repository.findById(2L)).isEmpty();
		assertThat(repository.findActiveByIdForShare(2L)).isEmpty();
		assertThat(repository.findByIdForUpdate(2L)).isEmpty();
		assertThat(repository.findDeletionState(2L))
			.hasValueSatisfying(state -> {
				assertThat(state.getAuthorId()).isEqualTo(1L);
				assertThat(state.getDeletedAt()).isNotNull();
			});
	}

	private void insertQuestion(long userId, String title, String deletedAt) {
		long pinId = jdbc.queryForObject("""
			INSERT INTO pins (author_id, pin_type, location, address)
			VALUES (?, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, '서울특별시')
			RETURNING pin_id
			""", Long.class, userId);
		jdbc.update("""
			INSERT INTO questions (pin_id, author_id, title, content, deleted_at)
			VALUES (?, ?, ?, 'content', CAST(? AS timestamptz))
			""", pinId, userId, title, deletedAt);
	}
}
