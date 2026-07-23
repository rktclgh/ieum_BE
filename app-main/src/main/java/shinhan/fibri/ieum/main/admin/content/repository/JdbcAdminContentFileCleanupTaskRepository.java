package shinhan.fibri.ieum.main.admin.content.repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdminContentFileCleanupTaskRepository implements AdminContentFileCleanupTaskRepository {

	private static final int MAX_SUPPORTED_ATTEMPTS = 20;

	private final JdbcClient jdbc;

	public JdbcAdminContentFileCleanupTaskRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<AdminContentFileCleanupTask> claimNext(String workerId, Duration lease, int maxAttempts) {
		validate(workerId, lease, maxAttempts);
		UUID leaseToken = UUID.randomUUID();
		return jdbc.sql("""
			WITH candidate AS (
				SELECT task_id
				FROM file_cleanup_tasks
				WHERE status IN ('pending', 'retry')
				  AND next_attempt_at <= CURRENT_TIMESTAMP
				  AND attempts < :maxAttempts
				ORDER BY next_attempt_at, created_at, task_id
				FOR UPDATE SKIP LOCKED
				LIMIT 1
			)
			UPDATE file_cleanup_tasks f
			SET status = 'processing',
			    lease_token = :leaseToken,
			    attempts = f.attempts + 1,
			    next_attempt_at = NULL,
			    lease_until = CURRENT_TIMESTAMP + (:leaseSeconds * INTERVAL '1 second'),
			    locked_by = :workerId,
			    last_error_code = NULL,
			    last_error_message = NULL,
			    updated_at = CURRENT_TIMESTAMP
			FROM candidate
			WHERE f.task_id = candidate.task_id
			RETURNING f.task_id, f.s3_key, f.lease_until, f.lease_token, f.attempts
			""")
			.param("leaseToken", leaseToken)
			.param("leaseSeconds", lease.toSeconds())
			.param("workerId", workerId)
			.param("maxAttempts", maxAttempts)
			.query((rs, rowNumber) -> new AdminContentFileCleanupTask(
				rs.getLong("task_id"),
				rs.getString("s3_key"),
				rs.getObject("lease_until", OffsetDateTime.class),
				rs.getObject("lease_token", UUID.class),
				rs.getInt("attempts")
			))
			.optional();
	}

	@Override
	public void enqueue(List<String> s3Keys) {
		if (s3Keys == null || s3Keys.isEmpty()) {
			return;
		}
		String sql = """
			INSERT INTO file_cleanup_tasks (s3_key, status, next_attempt_at, created_at, updated_at)
			VALUES (:s3Key, 'pending', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			ON CONFLICT (s3_key) DO NOTHING
			""";
		for (String s3Key : s3Keys) {
			if (s3Key == null || s3Key.isBlank()) {
				continue;
			}
			jdbc.sql(sql)
				.param("s3Key", s3Key)
				.update();
		}
	}

	@Override
	public boolean markCompleted(long taskId, UUID leaseToken) {
		validateFencedTransition(taskId, leaseToken);
		return jdbc.sql("""
			UPDATE file_cleanup_tasks
			SET status = 'completed',
			    lease_token = NULL,
			    lease_until = NULL,
			    locked_by = NULL,
			    next_attempt_at = NULL,
			    last_error_code = NULL,
			    last_error_message = NULL,
			    completed_at = CURRENT_TIMESTAMP,
			    updated_at = CURRENT_TIMESTAMP
			WHERE task_id = :taskId
			  AND status = 'processing'
			  AND lease_token = :leaseToken
			  AND lease_until > CURRENT_TIMESTAMP
			""")
			.param("taskId", taskId)
			.param("leaseToken", leaseToken)
			.update() == 1;
	}

	@Override
	public boolean markRetry(long taskId, UUID leaseToken, OffsetDateTime nextAttemptAt, String errorCode, String errorMessage) {
		validateFencedTransition(taskId, leaseToken);
		if (nextAttemptAt == null) {
			throw new IllegalArgumentException("nextAttemptAt must not be null");
		}
		return jdbc.sql("""
			UPDATE file_cleanup_tasks
			SET status = 'retry',
			    lease_token = NULL,
			    lease_until = NULL,
			    locked_by = NULL,
			    next_attempt_at = :nextAttemptAt,
			    last_error_code = :errorCode,
			    last_error_message = :errorMessage,
			    updated_at = CURRENT_TIMESTAMP
			WHERE task_id = :taskId
			  AND status = 'processing'
			  AND lease_token = :leaseToken
			  AND lease_until > CURRENT_TIMESTAMP
			""")
			.param("taskId", taskId)
			.param("leaseToken", leaseToken)
			.param("nextAttemptAt", nextAttemptAt)
			.param("errorCode", errorCode)
			.param("errorMessage", errorMessage)
			.update() == 1;
	}

	@Override
	public boolean markDead(long taskId, UUID leaseToken, String errorCode, String errorMessage) {
		validateFencedTransition(taskId, leaseToken);
		return jdbc.sql("""
			UPDATE file_cleanup_tasks
			SET status = 'dead',
			    lease_token = NULL,
			    lease_until = NULL,
			    locked_by = NULL,
			    next_attempt_at = NULL,
			    last_error_code = :errorCode,
			    last_error_message = :errorMessage,
			    updated_at = CURRENT_TIMESTAMP
			WHERE task_id = :taskId
			  AND status = 'processing'
			  AND lease_token = :leaseToken
			  AND lease_until > CURRENT_TIMESTAMP
			""")
			.param("taskId", taskId)
			.param("leaseToken", leaseToken)
			.param("errorCode", errorCode)
			.param("errorMessage", errorMessage)
			.update() == 1;
	}

	@Override
	public int recoverExpiredLeases(OffsetDateTime now, int maxAttempts) {
		if (now == null) {
			throw new IllegalArgumentException("now must not be null");
		}
		if (maxAttempts < 1 || maxAttempts > MAX_SUPPORTED_ATTEMPTS) {
			throw new IllegalArgumentException("maxAttempts must be between 1 and " + MAX_SUPPORTED_ATTEMPTS);
		}
		return jdbc.sql("""
			UPDATE file_cleanup_tasks
			SET status = CASE
					WHEN attempts >= :maxAttempts THEN 'dead'::text
					ELSE 'retry'::text
				  END,
			    lease_token = NULL,
			    lease_until = NULL,
			    locked_by = NULL,
			    next_attempt_at = CASE WHEN attempts >= :maxAttempts THEN NULL ELSE CURRENT_TIMESTAMP END,
			    last_error_code = CASE
					WHEN attempts >= :maxAttempts THEN 'LEASE_EXPIRED_MAX_ATTEMPTS'
					ELSE 'LEASE_EXPIRED'
				  END,
			    last_error_message = 'File cleanup task lease expired before completion',
			    updated_at = CURRENT_TIMESTAMP
			WHERE status = 'processing'
			  AND lease_until <= :now
			""")
			.param("now", now)
			.param("maxAttempts", maxAttempts)
			.update();
	}

	private void validate(String workerId, Duration lease, int maxAttempts) {
		if (workerId == null || workerId.isBlank() || workerId.length() > 120) {
			throw new IllegalArgumentException("workerId must contain 1 to 120 characters");
		}
		if (lease == null || lease.isZero() || lease.isNegative() || lease.toSeconds() < 1) {
			throw new IllegalArgumentException("lease must be at least one second");
		}
		if (maxAttempts < 1 || maxAttempts > MAX_SUPPORTED_ATTEMPTS) {
			throw new IllegalArgumentException("maxAttempts must be between 1 and " + MAX_SUPPORTED_ATTEMPTS);
		}
	}

	private void validateFencedTransition(long taskId, UUID leaseToken) {
		if (taskId < 1) {
			throw new IllegalArgumentException("taskId must be positive");
		}
		if (leaseToken == null) {
			throw new IllegalArgumentException("leaseToken must not be null");
		}
	}
}
