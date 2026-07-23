package shinhan.fibri.ieum.main.admin.content.repository;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.main.admin.content.domain.AdminContentType;

public record AdminContentHardDeleteTarget(
	AdminContentType contentType,
	Long contentId,
	Long pinId,
	String title,
	String authorNickname,
	Long authorId,
	OffsetDateTime createdAt,
	OffsetDateTime deletedAt
) {
	public boolean wasSoftDeleted() {
		return deletedAt != null;
	}
}
