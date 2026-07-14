package shinhan.fibri.ieum.main.admin.user.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.main.report.domain.ReportStatus;

public record AdminReportItem(
	Long reportId,
	ReportReason reason,
	ReportStatus status,
	Long reporterId,
	String reporterNickname,
	Long messageId,
	String detail,
	OffsetDateTime createdAt
) {
}
