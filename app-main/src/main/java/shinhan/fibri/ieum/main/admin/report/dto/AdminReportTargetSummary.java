package shinhan.fibri.ieum.main.admin.report.dto;

import shinhan.fibri.ieum.main.report.domain.ReportTargetType;

public record AdminReportTargetSummary(
	ReportTargetType type,
	Long id,
	boolean deleted
) {
}
