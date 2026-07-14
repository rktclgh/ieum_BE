package shinhan.fibri.ieum.main.admin.report.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.main.report.domain.ReportStatus;

public record AdminReportResolution(
	ReportStatus decision,
	AdminReportUserSummary resolvedBy,
	OffsetDateTime resolvedAt
) {
}
