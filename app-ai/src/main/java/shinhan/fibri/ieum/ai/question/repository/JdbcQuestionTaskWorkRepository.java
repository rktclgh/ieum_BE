package shinhan.fibri.ieum.ai.question.repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcQuestionTaskWorkRepository implements QuestionTaskWorkRepository {

	private static final int MAX_SUPPORTED_ATTEMPTS = 5;

	private final JdbcClient jdbc;

	public JdbcQuestionTaskWorkRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<ClaimedQuestionTask> claimNext(String workerId, Duration lease, int maxAttempts) {
		validate(workerId, lease, maxAttempts);
		UUID leaseToken = UUID.randomUUID();
		return jdbc.sql("""
			WITH candidate AS (
			    SELECT task.question_id
			    FROM ai_question_tasks task
			    JOIN questions question ON question.question_id = task.question_id
			    JOIN pins pin ON pin.pin_id = question.pin_id
			    WHERE task.status IN ('pending', 'retry')
			      AND task.next_attempt_at <= CURRENT_TIMESTAMP
			      AND task.attempts < :maxAttempts
			      AND question.deleted_at IS NULL
			      AND pin.deleted_at IS NULL
			    ORDER BY task.next_attempt_at, task.created_at, task.question_id
			    FOR UPDATE OF task SKIP LOCKED
			    LIMIT 1
			)
			UPDATE ai_question_tasks task
			SET status = 'processing',
			    stage = 'analyzing',
			    attempts = task.attempts + 1,
			    lease_until = CURRENT_TIMESTAMP + (:leaseSeconds * INTERVAL '1 second'),
			    locked_by = :workerId,
			    lease_token = :leaseToken,
			    last_error_code = NULL,
			    last_error_message = NULL,
			    started_at = COALESCE(task.started_at, CURRENT_TIMESTAMP)
			FROM candidate
			WHERE task.question_id = candidate.question_id
			RETURNING task.question_id, task.lease_token, task.lease_until, task.attempts
			""")
			.param("maxAttempts", maxAttempts)
			.param("leaseSeconds", lease.toSeconds())
			.param("workerId", workerId)
			.param("leaseToken", leaseToken)
			.query((resultSet, rowNumber) -> new ClaimedQuestionTask(
				resultSet.getLong("question_id"),
				resultSet.getObject("lease_token", UUID.class),
				resultSet.getObject("lease_until", OffsetDateTime.class),
				resultSet.getInt("attempts")
			))
			.optional();
	}

	@Override
	public boolean markRetry(
		long questionId,
		String workerId,
		UUID leaseToken,
		OffsetDateTime nextAttemptAt,
		String errorCode,
		String errorMessage
	) {
		validateRetryTransition(questionId, workerId, leaseToken, nextAttemptAt, errorCode, errorMessage);
		return jdbc.sql("""
			UPDATE ai_question_tasks
			SET status = 'retry',
			    lease_until = NULL,
			    locked_by = NULL,
			    lease_token = NULL,
			    next_attempt_at = :nextAttemptAt,
			    last_error_code = :errorCode,
			    last_error_message = :errorMessage
			WHERE question_id = :questionId
			  AND status = 'processing'
			  AND locked_by = :workerId
			  AND lease_token = :leaseToken
			  AND lease_until > CURRENT_TIMESTAMP
			""")
			.param("questionId", questionId)
			.param("workerId", workerId)
			.param("leaseToken", leaseToken)
			.param("nextAttemptAt", nextAttemptAt)
			.param("errorCode", errorCode)
			.param("errorMessage", errorMessage)
			.update() == 1;
	}

	private void validate(String workerId, Duration lease, int maxAttempts) {
		if (workerId == null || workerId.isBlank() || workerId.length() > 100) {
			throw new IllegalArgumentException("workerId must contain 1 to 100 characters");
		}
		if (lease == null || lease.isZero() || lease.isNegative() || lease.toSeconds() < 1) {
			throw new IllegalArgumentException("lease must be at least one second");
		}
		if (maxAttempts < 1 || maxAttempts > MAX_SUPPORTED_ATTEMPTS) {
			throw new IllegalArgumentException("maxAttempts must be between 1 and " + MAX_SUPPORTED_ATTEMPTS);
		}
	}

	private void validateRetryTransition(
		long questionId,
		String workerId,
		UUID leaseToken,
		OffsetDateTime nextAttemptAt,
		String errorCode,
		String errorMessage
	) {
		if (questionId < 1) {
			throw new IllegalArgumentException("questionId must be positive");
		}
		if (workerId == null || workerId.isBlank() || workerId.length() > 100) {
			throw new IllegalArgumentException("workerId must contain 1 to 100 characters");
		}
		if (leaseToken == null) {
			throw new IllegalArgumentException("leaseToken must not be null");
		}
		if (nextAttemptAt == null) {
			throw new IllegalArgumentException("nextAttemptAt must not be null");
		}
		if (errorCode == null || errorCode.isBlank() || errorCode.length() > 100) {
			throw new IllegalArgumentException("errorCode must contain 1 to 100 characters");
		}
		if (errorMessage != null && errorMessage.length() > 500) {
			throw new IllegalArgumentException("errorMessage must not exceed 500 characters");
		}
	}
}
