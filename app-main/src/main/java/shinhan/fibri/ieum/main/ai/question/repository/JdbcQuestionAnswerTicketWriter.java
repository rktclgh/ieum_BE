package shinhan.fibri.ieum.main.ai.question.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcQuestionAnswerTicketWriter implements QuestionAnswerTicketWriter {

	private final JdbcClient jdbc;

	@Override
	public void create(Long questionId) {
		jdbc.sql("""
			INSERT INTO ai_question_tasks (question_id)
			VALUES (:questionId)
			ON CONFLICT (question_id) DO NOTHING
			""")
			.param("questionId", questionId)
			.update();
	}

	@Override
	public void requestCancellation(Long questionId) {
		jdbc.sql("""
			UPDATE ai_question_tasks
			   SET cancel_requested_at = COALESCE(cancel_requested_at, now()),
			       updated_at = now()
			 WHERE question_id = :questionId
			   AND status IN ('pending', 'retry', 'processing')
			""")
			.param("questionId", questionId)
			.update();
	}

	@Override
	public boolean requestRegeneration(Long questionId) {
		int updated = jdbc.sql("""
			UPDATE ai_question_tasks
			   SET status = 'pending',
			       stage = 'discovered',
			       attempts = 0,
			       next_attempt_at = now(),
			       lease_until = NULL,
			       locked_by = NULL,
			       lease_token = NULL,
			       cancel_requested_at = NULL,
			       cancelled_at = NULL,
			       started_at = NULL,
			       completed_at = NULL,
			       answer_id = NULL,
			       answer_outcome = NULL,
			       answer_notification_processed_at = NULL,
			       embedding = NULL,
			       embedding_model = NULL,
			       grounding_status = NULL,
			       grounding_score = NULL,
			       evidence = '[]'::jsonb,
			       generation_provider = NULL,
			       generation_model = NULL,
			       retrieval_config_version = NULL,
			       fallback_reason = NULL,
			       prompt_version = NULL,
			       last_error_code = NULL,
			       last_error_message = NULL,
			       geo_scope = NULL,
			       geo_scope_confidence = NULL,
			       region_context = '{}'::jsonb,
			       updated_at = now()
			 WHERE question_id = :questionId
			   AND status <> 'completed'
			""")
			.param("questionId", questionId)
			.update();
		return updated > 0;
	}
}
