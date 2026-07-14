package shinhan.fibri.ieum.main.admin.report.dto;

import java.time.OffsetDateTime;
import java.util.List;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.main.report.domain.ReportStatus;
import tools.jackson.databind.JsonNode;

public record AdminReportDetailResponse(
	Long reportId,
	AdminReportTargetSummary target,
	AdminReportUserSummary reporter,
	AdminReportUserSummary reportedUser,
	ReportReason reason,
	String detail,
	ReportStatus status,
	JsonNode contextSnapshot,
	String contextHash,
	AdminReportAiDetail ai,
	AdminReportResolution resolution,
	List<AdminReportSanctionItem> sanctions,
	OffsetDateTime createdAt
) {
}
