package shinhan.fibri.ieum.ai.db;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class AiQuestionTaskEmbeddingModelConstraintIntegrationTest {

	private static final String DATABASE = "ieum_ai_question_embedding_model_ck";

	private JdbcClient jdbc;

	@BeforeEach
	void setUpSchema() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
	}

	@Test
	void allowsNullEmbeddingModel() {
		insertTask(null);
	}

	@Test
	void allowsGeminiEmbeddingModel() {
		insertTask("gemini-embedding-2");
	}

	@Test
	void rejectsNonGeminiEmbeddingModel() {
		assertThatThrownBy(() -> insertTask("gemini-embedding-001"))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_ai_question_tasks_embedding_model");
	}

	private void insertTask(String embeddingModel) {
		Long questionId = insertQuestion();
		jdbc.sql("""
			INSERT INTO ai_question_tasks (question_id, embedding, embedding_model)
			VALUES (:questionId,
			        CASE WHEN :embeddingModel::varchar IS NULL THEN NULL ELSE array_fill(0.0::real, ARRAY[768])::vector END,
			        :embeddingModel)
			""")
			.param("questionId", questionId)
			.param("embeddingModel", embeddingModel)
			.update();
	}

	private Long insertQuestion() {
		Long userId = jdbc.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (:email, 'hash', :nickname, true)
			RETURNING user_id
			""")
			.param("email", uniqueValue("user", "@example.com"))
			.param("nickname", uniqueValue("user", ""))
			.query(Long.class)
			.single();
		Long pinId = jdbc.sql("""
			INSERT INTO pins (author_id, pin_type, location, address)
			VALUES (:userId, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, 'Seoul')
			RETURNING pin_id
			""").param("userId", userId).query(Long.class).single();
		return jdbc.sql("""
			INSERT INTO questions (pin_id, author_id, title, content)
			VALUES (:pinId, :userId, 'title', 'content')
			RETURNING question_id
			""").param("pinId", pinId).param("userId", userId).query(Long.class).single();
	}

	private static String uniqueValue(String prefix, String suffix) {
		return prefix + "-" + System.nanoTime() + suffix;
	}
}
