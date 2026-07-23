package shinhan.fibri.ieum.main.admin.content.dto;

import java.time.OffsetDateTime;

public record AdminContentListItem(
	String contentType,
	Long contentId,
	String title,
	String authorNickname,
	Long authorId,
	OffsetDateTime createdAt,
	OffsetDateTime updatedAt,
	OffsetDateTime deletedAt,
	Boolean resolved,
	String status,
	Integer participantCount
) {
}
