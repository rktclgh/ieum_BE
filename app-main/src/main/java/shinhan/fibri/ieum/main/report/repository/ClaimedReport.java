package shinhan.fibri.ieum.main.report.repository;

import java.time.OffsetDateTime;
import java.util.UUID;
import shinhan.fibri.ieum.main.report.domain.ReportReason;

public record ClaimedReport(
	long reportId,
	Long reportedMessageId,
	long reporterId,
	long reportedUserId,
	ReportReason reason,
	String detail,
	String contextSnapshot,
	String contextHash,
	UUID attemptId,
	int attempts,
	OffsetDateTime leaseUntil
) {
}
