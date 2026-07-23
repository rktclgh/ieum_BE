package shinhan.fibri.ieum.main.admin.content.dto;

import java.time.OffsetDateTime;

public record AdminContentPreviewResponse(
	String contentType,
	Long contentId,
	String title,
	String authorNickname,
	Long authorId,
	OffsetDateTime createdAt,
	OffsetDateTime deletedAt
) {
}
