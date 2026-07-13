package shinhan.fibri.ieum.main.notification.internal;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAiQuestionAnswerCompletionRepository implements AiQuestionAnswerCompletionRepository {

	private final JdbcClient jdbc;

	public JdbcAiQuestionAnswerCompletionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<LockedTicket> lockTicket(Long questionId) {
		return jdbc.sql("""
			SELECT status::text AS status,
			       answer_id,
			       answer_notification_processed_at
			  FROM ai_question_tasks
			 WHERE question_id = :questionId
			 FOR UPDATE
			""")
			.param("questionId", questionId)
			.query((resultSet, rowNumber) -> new LockedTicket(
				resultSet.getString("status"),
				resultSet.getObject("answer_id", Long.class),
				resultSet.getObject("answer_notification_processed_at", OffsetDateTime.class)
			))
			.optional();
	}

	@Override
	public Optional<LockedQuestion> lockQuestion(Long questionId) {
		return jdbc.sql("""
			SELECT pin_id,
			       author_id,
			       deleted_at IS NOT NULL AS deleted
			  FROM questions
			 WHERE question_id = :questionId
			 FOR SHARE
			""")
			.param("questionId", questionId)
			.query((resultSet, rowNumber) -> new LockedQuestion(
				resultSet.getLong("pin_id"),
				resultSet.getLong("author_id"),
				resultSet.getBoolean("deleted")
			))
			.optional();
	}

	@Override
	public Optional<LockedPin> lockPin(Long pinId) {
		return jdbc.sql("""
			SELECT deleted_at IS NOT NULL AS deleted
			  FROM pins
			 WHERE pin_id = :pinId
			 FOR SHARE
			""")
			.param("pinId", pinId)
			.query((resultSet, rowNumber) -> new LockedPin(resultSet.getBoolean("deleted")))
			.optional();
	}

	@Override
	public boolean isMatchingAiAnswer(Long questionId, Long answerId) {
		return Boolean.TRUE.equals(jdbc.sql("""
			SELECT EXISTS (
				SELECT 1
				  FROM answers
				 WHERE answer_id = :answerId
				   AND question_id = :questionId
				   AND is_ai = TRUE
			)
			""")
			.param("answerId", answerId)
			.param("questionId", questionId)
			.query(Boolean.class)
			.single());
	}

	@Override
	public int acknowledgeNotification(Long questionId, Long answerId) {
		return jdbc.sql("""
			UPDATE ai_question_tasks
			   SET answer_notification_processed_at = CURRENT_TIMESTAMP,
			       updated_at = CURRENT_TIMESTAMP
			 WHERE question_id = :questionId
			   AND status = 'completed'
			   AND answer_id = :answerId
			   AND answer_notification_processed_at IS NULL
			""")
			.param("questionId", questionId)
			.param("answerId", answerId)
			.update();
	}
}
