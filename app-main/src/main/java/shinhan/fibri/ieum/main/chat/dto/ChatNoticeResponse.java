package shinhan.fibri.ieum.main.chat.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.common.chat.domain.ChatNotice;

public record ChatNoticeResponse(
	Long noticeId,
	Long roomId,
	ChatMessageResponse message,
	Long createdByUserId,
	OffsetDateTime createdAt,
	boolean pinned
) {

	public static ChatNoticeResponse from(ChatNotice notice, long visibleAfterMessageId, Long pinnedNoticeId) {
		return new ChatNoticeResponse(
			notice.getId(),
			notice.getRoom().getId(),
			ChatMessageResponse.from(notice.getMessage(), visibleAfterMessageId),
			notice.getCreatedBy() == null ? null : notice.getCreatedBy().getId(),
			notice.getCreatedAt(),
			notice.getId().equals(pinnedNoticeId)
		);
	}
}
