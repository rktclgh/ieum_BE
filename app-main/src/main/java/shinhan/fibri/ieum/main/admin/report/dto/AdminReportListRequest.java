package shinhan.fibri.ieum.main.admin.report.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import shinhan.fibri.ieum.main.report.domain.ReportAiReviewState;
import shinhan.fibri.ieum.main.report.domain.ReportStatus;

public record AdminReportListRequest(
	ReportStatus status,
	ReportAiReviewState aiReviewState,
	AdminReportDecision decision,
	String cursor,
	@Min(1)
	@Max(50)
	Integer size
) {
}
