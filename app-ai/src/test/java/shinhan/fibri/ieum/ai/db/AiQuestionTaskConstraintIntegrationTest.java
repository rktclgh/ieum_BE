package shinhan.fibri.ieum.ai.db;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class AiQuestionTaskConstraintIntegrationTest {

	private static final String DATABASE = "ieum_ai_question_ck";

	private JdbcClient jdbc;
	private Long questionId;

	@BeforeEach
	void setUpSchema() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		questionId = insertPendingTask(jdbc);
	}

	@Test
	void allowsInsufficientEvidenceCompletionWithoutAnswerRow() {
		jdbc.sql("""
			UPDATE ai_question_tasks
			SET status='completed',
			    completed_at=now(),
			    embedding=array_fill(0.0::real, ARRAY[768])::vector,
			    embedding_model='gemini-embedding-2',
			    answer_outcome='insufficient_evidence',
			    grounding_status='insufficient_evidence',
			    answer_id=NULL,
			    generation_provider=NULL,
			    generation_model=NULL
			WHERE question_id = :questionId
			""").param("questionId", questionId).update();
	}

	@Test
	void allowsUngroundedCompletionWithPromptVersionAndNoEvidence() {
		Long answerId = insertAiAnswer(jdbc, questionId);

		jdbc.sql("""
			UPDATE ai_question_tasks
			SET status='completed',
			    completed_at=now(),
			    embedding=array_fill(0.0::real, ARRAY[768])::vector,
			    embedding_model='gemini-embedding-2',
			    answer_id=:answerId,
			    answer_outcome='ungrounded',
			    generation_provider='gemini',
			    generation_model='gemini-3.1-flash-lite',
			    prompt_version='question-ungrounded-answer-v1',
			    grounding_status='ungrounded',
			    evidence='[]'::jsonb
			WHERE question_id = :questionId
			""").param("questionId", questionId).param("answerId", answerId).update();
	}

	@Test
	void rejectsUngroundedCompletionWithoutPromptVersion() {
		Long answerId = insertAiAnswer(jdbc, questionId);

		assertThatThrownBy(() -> jdbc.sql("""
			UPDATE ai_question_tasks
			SET status='completed',
			    completed_at=now(),
			    embedding=array_fill(0.0::real, ARRAY[768])::vector,
			    embedding_model='gemini-embedding-2',
			    answer_id=:answerId,
			    answer_outcome='ungrounded',
			    generation_provider='gemini',
			    generation_model='gemini-3.1-flash-lite',
			    prompt_version=NULL,
			    grounding_status='ungrounded',
			    evidence='[]'::jsonb
			WHERE question_id = :questionId
			""").param("questionId", questionId).param("answerId", answerId).update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_ai_question_tasks_completed");
	}

	@Test
	void rejectsProcessingWithoutFencingToken() {
		assertThatThrownBy(() -> jdbc.sql("""
			UPDATE ai_question_tasks
			SET status='processing', lease_until=now()+interval '2 minutes', locked_by='worker-1', lease_token=NULL
			WHERE question_id = :questionId
			""").param("questionId", questionId).update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_ai_question_tasks_processing_lease");
	}

	private static Long insertPendingTask(JdbcClient jdbc) {
		Long userId = jdbc.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES ('ai-question-ck@example.com', 'hash', 'ai-question-ck-user', true)
			RETURNING user_id
			""").query(Long.class).single();
		Long pinId = jdbc.sql("""
			INSERT INTO pins (author_id, pin_type, location, address)
			VALUES (:userId, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, 'Seoul')
			RETURNING pin_id
			""").param("userId", userId).query(Long.class).single();
		Long insertedQuestionId = jdbc.sql("""
			INSERT INTO questions (pin_id, author_id, title, content)
			VALUES (:pinId, :userId, 'title', 'content')
			RETURNING question_id
			""").param("pinId", pinId).param("userId", userId).query(Long.class).single();
		jdbc.sql("""
			INSERT INTO ai_question_tasks (question_id)
			VALUES (:questionId)
			""").param("questionId", insertedQuestionId).update();
		return insertedQuestionId;
	}

	private static Long insertAiAnswer(JdbcClient jdbc, Long questionId) {
		return jdbc.sql("""
			INSERT INTO answers (question_id, author_id, is_ai, content)
			VALUES (:questionId, NULL, TRUE, 'AI answer')
			RETURNING answer_id
			""").param("questionId", questionId).query(Long.class).single();
	}
}
