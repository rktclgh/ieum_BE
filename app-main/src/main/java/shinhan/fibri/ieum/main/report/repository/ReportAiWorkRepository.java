package shinhan.fibri.ieum.main.report.repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ReportAiWorkRepository {

	Optional<ClaimedReport> claimNext(String workerId, Duration lease, int maxAttempts);

	boolean markRetry(long reportId, UUID attemptId, OffsetDateTime nextAttemptAt, String errorCode, String errorMessage);

	boolean markDead(long reportId, UUID attemptId, String errorCode, String errorMessage);

	int recoverExpiredLeases(OffsetDateTime now, int maxAttempts);
}
