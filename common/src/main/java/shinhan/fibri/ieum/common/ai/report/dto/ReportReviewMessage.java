package shinhan.fibri.ieum.common.ai.report.dto;

public record ReportReviewMessage(
	long messageId,
	String actor,
	String content,
	ReportReviewImage image,
	String createdAt
) {
}
