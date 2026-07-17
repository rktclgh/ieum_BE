package shinhan.fibri.ieum.main.chat.service;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.domain.MessageType;
import shinhan.fibri.ieum.main.chat.dto.ChatMessageResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatReplyPreview;

public record WsMessageEvent(
	Long messageId,
	Long roomId,
	Long senderId,
	String senderNickname,
	String senderProfileImageUrl,
	MessageType messageType,
	String content,
	String imageUrl,
	OffsetDateTime createdAt,
	ChatReplyPreview replyTo
) {

	public WsMessageEvent(
		Long messageId,
		Long roomId,
		Long senderId,
		String senderNickname,
		String senderProfileImageUrl,
		MessageType messageType,
		String content,
		String imageUrl,
		OffsetDateTime createdAt
	) {
		this(
			messageId,
			roomId,
			senderId,
			senderNickname,
			senderProfileImageUrl,
			messageType,
			content,
			imageUrl,
			createdAt,
			null
		);
	}

	public static WsMessageEvent from(Message message) {
		ChatMessageResponse response = ChatMessageResponse.from(message);
		return new WsMessageEvent(
			response.messageId(),
			response.roomId(),
			response.senderId(),
			response.senderNickname(),
			response.senderProfileImageUrl(),
			response.messageType(),
			response.content(),
			response.imageUrl(),
			response.createdAt(),
			response.replyTo()
		);
	}

	public WsMessageEvent withReplyToVisibleAfter(long visibleAfterMessageId) {
		if (replyTo == null || replyTo.messageId() == null || replyTo.messageId() > visibleAfterMessageId) {
			return this;
		}
		return new WsMessageEvent(
			messageId,
			roomId,
			senderId,
			senderNickname,
			senderProfileImageUrl,
			messageType,
			content,
			imageUrl,
			createdAt,
			null
		);
	}
}
