package shinhan.fibri.ieum.main.chat.dto;

import java.util.List;

public record ChatNoticePageResponse(
	List<ChatNoticeResponse> items,
	String nextCursor,
	ChatNoticeResponse pinnedNotice
) {
}
