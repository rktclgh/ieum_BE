package shinhan.fibri.ieum.main.admin.content.repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminContentFileCleanupTaskRepository {

	Optional<AdminContentFileCleanupTask> claimNext(String workerId, Duration lease, int maxAttempts);

	void enqueue(List<String> s3Keys);

	boolean markCompleted(long taskId, UUID leaseToken);

	boolean markRetry(long taskId, UUID leaseToken, OffsetDateTime nextAttemptAt, String errorCode, String errorMessage);

	boolean markDead(long taskId, UUID leaseToken, String errorCode, String errorMessage);

	int recoverExpiredLeases(OffsetDateTime now, int maxAttempts);
}
