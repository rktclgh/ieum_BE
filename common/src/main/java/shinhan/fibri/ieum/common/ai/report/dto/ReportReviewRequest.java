package shinhan.fibri.ieum.common.ai.report.dto;

import java.util.List;
import java.util.UUID;

public record ReportReviewRequest(
	long reportId,
	UUID reviewAttemptId,
	long reportedMessageId,
	String reason,
	String detail,
	String contextHash,
	List<ReportReviewMessage> messages
) {
}
