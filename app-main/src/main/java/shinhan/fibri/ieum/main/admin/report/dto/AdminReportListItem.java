package shinhan.fibri.ieum.main.admin.report.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.main.report.domain.ReportStatus;

public record AdminReportListItem(
	Long reportId,
	AdminReportTargetSummary target,
	AdminReportUserSummary reporter,
	AdminReportUserSummary reportedUser,
	ReportReason reason,
	ReportStatus status,
	AdminReportAiSummary ai,
	OffsetDateTime createdAt
) {
}
