package shinhan.fibri.ieum.ai.report.service;

import java.util.List;
import java.util.UUID;
import shinhan.fibri.ieum.ai.report.domain.ReportReviewEvidenceMessage;

public record PreparedReportReview(
	long reportId,
	UUID reviewAttemptId,
	long reportedMessageId,
	String reason,
	String detail,
	String contextHash,
	List<ReportReviewEvidenceMessage> evidenceMessages,
	ReportEvidenceImageBatch imageBatch
) {

	public PreparedReportReview {
		if (reportId < 1 || reviewAttemptId == null || reportedMessageId < 1 || evidenceMessages == null || imageBatch == null) {
			throw new IllegalArgumentException("prepared report review must be valid");
		}
		evidenceMessages = List.copyOf(evidenceMessages);
	}
}
