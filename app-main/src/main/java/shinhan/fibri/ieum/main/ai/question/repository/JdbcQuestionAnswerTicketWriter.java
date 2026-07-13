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
}
