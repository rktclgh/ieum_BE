package shinhan.fibri.ieum.main.report.repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import shinhan.fibri.ieum.main.report.domain.ReportReason;

@Repository
public class JdbcReportAiWorkRepository implements ReportAiWorkRepository {

	private static final int MAX_SUPPORTED_ATTEMPTS = 5;

	private final JdbcClient jdbc;

	public JdbcReportAiWorkRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<ClaimedReport> claimNext(String workerId, Duration lease, int maxAttempts) {
		validate(workerId, lease, maxAttempts);
		UUID attemptId = UUID.randomUUID();
		return jdbc.sql("""
			WITH candidate AS (
			    SELECT report_id
			    FROM reports
			    WHERE ai_review_state IN ('pending', 'retry')
			      AND ai_next_attempt_at <= CURRENT_TIMESTAMP
			      AND ai_attempts < :maxAttempts
			    ORDER BY ai_next_attempt_at, created_at, report_id
			    FOR UPDATE SKIP LOCKED
			    LIMIT 1
			)
			UPDATE reports report
			SET ai_review_state = 'processing',
			    ai_review_attempt_id = :attemptId,
			    ai_attempts = report.ai_attempts + 1,
			    ai_next_attempt_at = NULL,
			    ai_lease_until = CURRENT_TIMESTAMP + (:leaseSeconds * INTERVAL '1 second'),
			    ai_locked_by = :workerId,
			    ai_last_error_code = NULL,
			    ai_last_error_message = NULL
			FROM candidate
			WHERE report.report_id = candidate.report_id
			RETURNING report.report_id, report.message_id, report.reporter_id, report.reported_user_id,
			          report.reason::text AS reason, report.detail, report.context_snapshot::text,
			          report.context_hash, report.ai_review_attempt_id, report.ai_attempts, report.ai_lease_until
			""")
			.param("attemptId", attemptId)
			.param("leaseSeconds", lease.toSeconds())
			.param("workerId", workerId)
			.param("maxAttempts", maxAttempts)
			.query((rs, rowNumber) -> new ClaimedReport(
				rs.getLong("report_id"),
				(Long) rs.getObject("message_id"),
				rs.getLong("reporter_id"),
				rs.getLong("reported_user_id"),
				ReportReason.valueOf(rs.getString("reason")),
				rs.getString("detail"),
				rs.getString("context_snapshot"),
				rs.getString("context_hash"),
				rs.getObject("ai_review_attempt_id", UUID.class),
				rs.getInt("ai_attempts"),
				rs.getObject("ai_lease_until", OffsetDateTime.class)
			))
			.optional();
	}

	@Override
	public boolean markRetry(
		long reportId,
		UUID attemptId,
		OffsetDateTime nextAttemptAt,
		String errorCode,
		String errorMessage
	) {
		validateFencedTransition(reportId, attemptId, errorCode, errorMessage);
		if (nextAttemptAt == null) {
			throw new IllegalArgumentException("nextAttemptAt must not be null");
		}
		return jdbc.sql("""
			UPDATE reports
			SET ai_review_state = 'retry',
			    ai_review_attempt_id = NULL,
			    ai_next_attempt_at = :nextAttemptAt,
			    ai_lease_until = NULL,
			    ai_locked_by = NULL,
			    ai_last_error_code = :errorCode,
			    ai_last_error_message = :errorMessage
			WHERE report_id = :reportId
			  AND ai_review_state = 'processing'
			  AND ai_review_attempt_id = :attemptId
			  AND ai_lease_until > CURRENT_TIMESTAMP
			""")
			.param("reportId", reportId)
			.param("attemptId", attemptId)
			.param("nextAttemptAt", nextAttemptAt)
			.param("errorCode", errorCode)
			.param("errorMessage", errorMessage)
			.update() == 1;
	}

	@Override
	public boolean markCompleted(long reportId, UUID attemptId, ReportAiReviewResult result) {
		validateCompletedTransition(reportId, attemptId, result);
		return jdbc.sql("""
			UPDATE reports
			SET ai_review_state = 'completed',
			    status = 'ai_reviewed',
			    ai_review_attempt_id = NULL,
			    ai_next_attempt_at = NULL,
			    ai_lease_until = NULL,
			    ai_locked_by = NULL,
			    ai_last_error_code = NULL,
			    ai_last_error_message = NULL,
			    ai_decision = CAST(:decision AS ai_report_decision),
			    ai_recommendation = CAST(:recommendation AS ai_recommendation),
			    ai_confidence = :confidence,
			    ai_reason = :reason,
			    ai_model_version = :modelVersion,
			    ai_policy_version = :policyVersion,
			    ai_policy_set_hash = :policySetHash,
			    ai_reviewed_at = :reviewedAt,
			    ai_review_result = CAST(:reviewResult AS jsonb)
			WHERE report_id = :reportId
			  AND ai_review_state = 'processing'
			  AND ai_review_attempt_id = :attemptId
			  AND ai_lease_until > CURRENT_TIMESTAMP
			""")
			.param("reportId", reportId)
			.param("attemptId", attemptId)
			.param("decision", result.decision())
			.param("recommendation", result.recommendation())
			.param("confidence", result.confidence())
			.param("reason", result.reason())
			.param("modelVersion", result.modelVersion())
			.param("policyVersion", result.policyVersion())
			.param("policySetHash", result.policySetHash())
			.param("reviewedAt", result.reviewedAt())
			.param("reviewResult", result.reviewResultJson())
			.update() == 1;
	}

	@Override
	public boolean markDead(long reportId, UUID attemptId, String errorCode, String errorMessage) {
		validateFencedTransition(reportId, attemptId, errorCode, errorMessage);
		return jdbc.sql("""
			UPDATE reports
			SET ai_review_state = 'dead',
			    ai_review_attempt_id = NULL,
			    ai_next_attempt_at = NULL,
			    ai_lease_until = NULL,
			    ai_locked_by = NULL,
			    ai_last_error_code = :errorCode,
			    ai_last_error_message = :errorMessage
			WHERE report_id = :reportId
			  AND ai_review_state = 'processing'
			  AND ai_review_attempt_id = :attemptId
			  AND ai_lease_until > CURRENT_TIMESTAMP
			""")
			.param("reportId", reportId)
			.param("attemptId", attemptId)
			.param("errorCode", errorCode)
			.param("errorMessage", errorMessage)
			.update() == 1;
	}

	@Override
	public int recoverExpiredLeases(OffsetDateTime now, int maxAttempts) {
		if (now == null) {
			throw new IllegalArgumentException("now must not be null");
		}
		validateMaxAttempts(maxAttempts);
		return jdbc.sql("""
			UPDATE reports
			SET ai_review_state = CASE
			        WHEN ai_attempts >= :maxAttempts THEN 'dead'::ai_job_status
			        ELSE 'retry'::ai_job_status
			    END,
			    ai_review_attempt_id = NULL,
			    ai_next_attempt_at = CASE WHEN ai_attempts >= :maxAttempts THEN NULL ELSE :now END,
			    ai_lease_until = NULL,
			    ai_locked_by = NULL,
			    ai_last_error_code = CASE
			        WHEN ai_attempts >= :maxAttempts THEN 'LEASE_EXPIRED_MAX_ATTEMPTS'
			        ELSE 'LEASE_EXPIRED'
			    END,
			    ai_last_error_message = 'Report AI worker lease expired before completion'
			WHERE ai_review_state = 'processing'
			  AND ai_lease_until <= :now
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
		validateMaxAttempts(maxAttempts);
	}

	private void validateCompletedTransition(long reportId, UUID attemptId, ReportAiReviewResult result) {
		if (reportId < 1) {
			throw new IllegalArgumentException("reportId must be positive");
		}
		if (attemptId == null) {
			throw new IllegalArgumentException("attemptId must not be null");
		}
		if (result == null) {
			throw new IllegalArgumentException("result must not be null");
		}
	}

	private void validateFencedTransition(long reportId, UUID attemptId, String errorCode, String errorMessage) {
		if (reportId < 1) {
			throw new IllegalArgumentException("reportId must be positive");
		}
		if (attemptId == null) {
			throw new IllegalArgumentException("attemptId must not be null");
		}
		if (errorCode == null || errorCode.isBlank() || errorCode.length() > 80) {
			throw new IllegalArgumentException("errorCode must contain 1 to 80 characters");
		}
		if (errorMessage != null && errorMessage.length() > 500) {
			throw new IllegalArgumentException("errorMessage must not exceed 500 characters");
		}
	}

	private void validateMaxAttempts(int maxAttempts) {
		if (maxAttempts < 1 || maxAttempts > MAX_SUPPORTED_ATTEMPTS) {
			throw new IllegalArgumentException("maxAttempts must be between 1 and " + MAX_SUPPORTED_ATTEMPTS);
		}
	}
}
