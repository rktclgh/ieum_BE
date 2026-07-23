package shinhan.fibri.ieum.main.admin.content.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminContentFileCleanupTask(
	long taskId,
	String s3Key,
	OffsetDateTime leaseUntil,
	UUID leaseToken,
	int attempts
) {
}
