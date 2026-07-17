package shinhan.fibri.ieum.main.admin.stats.dto;

public record AdminStatsQueueResponse(
	long pendingReportCount,
	long retryReportCount,
	long deadReportCount,
	long pendingInquiryCount
) {
}
