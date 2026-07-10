package shinhan.fibri.ieum.main.admin.user.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.main.admin.user.domain.SanctionType;

public record AdminSanctionItem(
	Long sanctionId,
	SanctionType type,
	String reason,
	OffsetDateTime createdAt,
	Long createdBy,
	OffsetDateTime endsAt,
	OffsetDateTime releasedAt,
	Long releasedBy
) {
}
