package shinhan.fibri.ieum.ai.question.callback;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcQuestionCompletionCallbackRepository implements QuestionCompletionCallbackRepository {

	private final JdbcClient jdbc;

	public JdbcQuestionCompletionCallbackRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<PendingQuestionCompletion> findPending(long questionId) {
		validateQuestionId(questionId);
		return jdbc.sql("""
			SELECT question_id, answer_id
			FROM ai_question_tasks
			WHERE question_id = :questionId
			  AND status = 'completed'
			  AND answer_id IS NOT NULL
			  AND answer_notification_processed_at IS NULL
			""")
			.param("questionId", questionId)
			.query((resultSet, rowNumber) -> new PendingQuestionCompletion(
				resultSet.getLong("question_id"),
				resultSet.getLong("answer_id")
			))
			.optional();
	}
	@Override
	public boolean existsByQuestionId(long questionId) {
		validateQuestionId(questionId);
		return jdbc.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM ai_question_tasks
			    WHERE question_id = :questionId
			)
			""")
			.param("questionId", questionId)
			.query(Boolean.class)
			.single();
	}

	private static void validateQuestionId(long questionId) {
		if (questionId <= 0) {
			throw new IllegalArgumentException("questionId must be positive");
		}
	}
}
