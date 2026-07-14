package shinhan.fibri.ieum.main.admin.report.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.main.admin.user.domain.SanctionType;

public record AdminReportSanctionItem(
	Long sanctionId,
	String decisionSource,
	SanctionType type,
	String reason,
	AdminReportUserSummary admin,
	OffsetDateTime startsAt,
	OffsetDateTime endsAt,
	OffsetDateTime releasedAt,
	AdminReportUserSummary releasedBy,
	OffsetDateTime createdAt
) {
}
