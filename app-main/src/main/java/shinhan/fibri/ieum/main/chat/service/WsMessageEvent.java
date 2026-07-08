package shinhan.fibri.ieum.main.chat.service;

import java.time.OffsetDateTime;

public record WsMessageEvent(
	Long messageId,
	Long roomId,
	Long senderId,
	String senderNickname,
	String content,
	String imageUrl,
	OffsetDateTime createdAt
) {
}
