package shinhan.fibri.ieum.ai.question.repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import shinhan.fibri.ieum.ai.question.service.QuestionTaskFailure;
import shinhan.fibri.ieum.ai.question.service.QuestionTaskFailureDisposition;

@Repository
public class JdbcQuestionTaskWorkRepository implements QuestionTaskWorkRepository {

	private static final int MAX_SUPPORTED_ATTEMPTS = 5;
	private static final String PROCESSING_FAILURE_CODE = "QUESTION_ANSWER_PROCESSING_FAILED";
	private static final String PROCESSING_FAILURE_MESSAGE = "Question answer processing failed";

	private final JdbcClient jdbc;

	public JdbcQuestionTaskWorkRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<ClaimedQuestionTask> claimByQuestionId(
		long questionId,
		String workerId,
		Duration lease,
		int maxAttempts
	) {
		validateClaim(questionId, workerId, lease, maxAttempts);
		String canonicalWorkerId = workerId.trim();
		UUID leaseToken = UUID.randomUUID();
		return jdbc.sql("""
			WITH exhausted AS (
			    UPDATE ai_question_tasks task
			    SET status = 'dead',
			        lease_until = NULL,
			        locked_by = NULL,
			        lease_token = NULL,
			        last_error_code = :errorCode,
			        last_error_message = :errorMessage,
			        updated_at = clock_timestamp()
			    FROM questions question
			    JOIN pins pin ON pin.pin_id = question.pin_id
			    WHERE task.question_id = :questionId
			      AND question.question_id = task.question_id
			      AND task.attempts >= :maxAttempts
			      AND task.cancel_requested_at IS NULL
			      AND question.deleted_at IS NULL
			      AND pin.deleted_at IS NULL
			      AND (
			          (task.status IN ('pending', 'retry') AND task.next_attempt_at <= clock_timestamp())
			          OR (task.status = 'processing' AND task.lease_until <= clock_timestamp())
			      )
			    RETURNING task.question_id
			), candidate AS (
			    SELECT task.question_id
			    FROM ai_question_tasks task
			    JOIN questions question ON question.question_id = task.question_id
			    JOIN pins pin ON pin.pin_id = question.pin_id
			    WHERE task.question_id = :questionId
			      AND task.attempts < :maxAttempts
			      AND task.cancel_requested_at IS NULL
			      AND question.deleted_at IS NULL
			      AND pin.deleted_at IS NULL
			      AND (
			          (task.status IN ('pending', 'retry') AND task.next_attempt_at <= clock_timestamp())
			          OR (task.status = 'processing' AND task.lease_until <= clock_timestamp())
			      )
			    FOR UPDATE OF task SKIP LOCKED
			)
			UPDATE ai_question_tasks task
			SET status = 'processing',
			    stage = 'analyzing',
			    attempts = task.attempts + 1,
			    lease_until = clock_timestamp() + (:leaseSeconds * INTERVAL '1 second'),
			    locked_by = :workerId,
			    lease_token = :leaseToken,
			    last_error_code = NULL,
			    last_error_message = NULL,
			    started_at = COALESCE(task.started_at, clock_timestamp()),
			    updated_at = clock_timestamp()
			FROM candidate
			WHERE task.question_id = candidate.question_id
			RETURNING task.question_id, task.locked_by, task.lease_token, task.lease_until, task.attempts
			""")
			.param("questionId", questionId)
			.param("maxAttempts", maxAttempts)
			.param("leaseSeconds", lease.toSeconds())
			.param("workerId", canonicalWorkerId)
			.param("leaseToken", leaseToken)
			.param("errorCode", PROCESSING_FAILURE_CODE)
			.param("errorMessage", PROCESSING_FAILURE_MESSAGE)
			.query((resultSet, rowNumber) -> new ClaimedQuestionTask(
				resultSet.getLong("question_id"),
				resultSet.getString("locked_by"),
				resultSet.getObject("lease_token", UUID.class),
				resultSet.getObject("lease_until", OffsetDateTime.class),
				resultSet.getInt("attempts")
			))
			.optional();
	}

	@Override
	public Optional<QuestionTaskDispatchSnapshot> findDispatchSnapshot(long questionId) {
		validateQuestionId(questionId);
		return jdbc.sql("""
			SELECT task.question_id,
			       task.status::text AS status,
			       (task.status = 'processing' AND task.lease_until > clock_timestamp()) AS active_lease,
			       task.cancel_requested_at IS NOT NULL AS cancellation_requested,
			       question.deleted_at IS NOT NULL AS question_deleted,
			       pin.deleted_at IS NOT NULL AS pin_deleted,
			       task.answer_id,
			       task.answer_notification_processed_at
			FROM ai_question_tasks task
			JOIN questions question ON question.question_id = task.question_id
			JOIN pins pin ON pin.pin_id = question.pin_id
			WHERE task.question_id = :questionId
			""")
			.param("questionId", questionId)
			.query((resultSet, rowNumber) -> new QuestionTaskDispatchSnapshot(
				resultSet.getLong("question_id"),
				QuestionTaskStatus.valueOf(resultSet.getString("status").toUpperCase(Locale.ROOT)),
				resultSet.getBoolean("active_lease"),
				resultSet.getBoolean("cancellation_requested"),
				resultSet.getBoolean("question_deleted"),
				resultSet.getBoolean("pin_deleted"),
				resultSet.getObject("answer_id", Long.class),
				resultSet.getObject("answer_notification_processed_at", OffsetDateTime.class)
			))
			.optional();
	}

	@Override
	public boolean markRetry(
		long questionId,
		String workerId,
		UUID leaseToken,
		Duration retryDelay,
		QuestionTaskFailure failure
	) {
		validateFailureTransition(questionId, workerId, leaseToken);
		validateRetryDelay(retryDelay);
		validateFailure(failure, QuestionTaskFailureDisposition.RETRY);
		return jdbc.sql("""
			UPDATE ai_question_tasks
			SET status = 'retry',
			    lease_until = NULL,
			    locked_by = NULL,
			    lease_token = NULL,
			    next_attempt_at = clock_timestamp() + (:retryDelaySeconds * INTERVAL '1 second'),
			    last_error_code = :errorCode,
			    last_error_message = :errorMessage,
			    updated_at = CURRENT_TIMESTAMP
			WHERE question_id = :questionId
			  AND status = 'processing'
			  AND cancel_requested_at IS NULL
			  AND locked_by = :workerId
			  AND lease_token = :leaseToken
			  AND lease_until > clock_timestamp()
			""")
			.param("questionId", questionId)
			.param("workerId", workerId)
			.param("leaseToken", leaseToken)
			.param("retryDelaySeconds", retryDelay.toSeconds())
			.param("errorCode", failure.errorCode())
			.param("errorMessage", failure.safeMessage())
			.update() == 1;
	}

	@Override
	public boolean markDead(
		long questionId,
		String workerId,
		UUID leaseToken,
		QuestionTaskFailure failure
	) {
		validateFailureTransition(questionId, workerId, leaseToken);
		validateFailure(failure, QuestionTaskFailureDisposition.DEAD, QuestionTaskFailureDisposition.RETRY);
		return jdbc.sql("""
			UPDATE ai_question_tasks
			SET status = 'dead',
			    lease_until = NULL,
			    locked_by = NULL,
			    lease_token = NULL,
			    last_error_code = :errorCode,
			    last_error_message = :errorMessage,
			    updated_at = CURRENT_TIMESTAMP
			WHERE question_id = :questionId
			  AND status = 'processing'
			  AND cancel_requested_at IS NULL
			  AND locked_by = :workerId
			  AND lease_token = :leaseToken
			  AND lease_until > clock_timestamp()
			""")
			.param("questionId", questionId)
			.param("workerId", workerId)
			.param("leaseToken", leaseToken)
			.param("errorCode", failure.errorCode())
			.param("errorMessage", failure.safeMessage())
			.update() == 1;
	}

	private void validateClaim(long questionId, String workerId, Duration lease, int maxAttempts) {
		validateQuestionId(questionId);
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

	private void validateQuestionId(long questionId) {
		if (questionId < 1) {
			throw new IllegalArgumentException("questionId must be positive");
		}
	}

	private void validateFailureTransition(
		long questionId,
		String workerId,
		UUID leaseToken
	) {
		validateQuestionId(questionId);
		if (workerId == null || workerId.isBlank() || workerId.length() > 100) {
			throw new IllegalArgumentException("workerId must contain 1 to 100 characters");
		}
		if (leaseToken == null) {
			throw new IllegalArgumentException("leaseToken must not be null");
		}
	}

	private void validateRetryDelay(Duration retryDelay) {
		if (retryDelay == null || retryDelay.isNegative() || retryDelay.isZero() || retryDelay.toSeconds() < 1) {
			throw new IllegalArgumentException("retryDelay must be at least one second");
		}
	}

	private void validateFailure(
		QuestionTaskFailure failure,
		QuestionTaskFailureDisposition... allowedDispositions
	) {
		if (failure == null) {
			throw new IllegalArgumentException("failure must not be null");
		}
		for (QuestionTaskFailureDisposition allowed : allowedDispositions) {
			if (failure.disposition() == allowed) {
				return;
			}
		}
		throw new IllegalArgumentException("failure disposition is not valid for this transition");
	}
}
