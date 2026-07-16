package shinhan.fibri.ieum.main.chat.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.domain.MessageType;
import shinhan.fibri.ieum.main.support.ProfileImageUrls;

public record ChatMessageResponse(
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

	public static ChatMessageResponse from(Message message) {
		return new ChatMessageResponse(
			message.getId(),
			message.getRoom().getId(),
			message.getSender().getId(),
			message.getSender().getNickname(),
			ProfileImageUrls.of(message.getSender()),
			message.getMessageType(),
			message.getContent(),
			imageUrl(message.getImageFileId()),
			message.getCreatedAt()
		);
	}

	private static String imageUrl(UUID imageFileId) {
		return imageFileId == null ? null : "/api/v1/files/%s".formatted(imageFileId);
	}
}
