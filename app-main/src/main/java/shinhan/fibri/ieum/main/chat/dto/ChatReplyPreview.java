package shinhan.fibri.ieum.main.chat.dto;

import java.util.UUID;
import shinhan.fibri.ieum.common.chat.domain.Message;

public record ChatReplyPreview(
	Long messageId,
	Long senderId,
	String senderNickname,
	String content,
	String imageUrl
) {

	public static ChatReplyPreview from(Message message) {
		return new ChatReplyPreview(
			message.getId(),
			message.getSender().getId(),
			message.getSender().getNickname(),
			message.getContent(),
			imageUrl(message.getImageFileId())
		);
	}

	private static String imageUrl(UUID imageFileId) {
		return imageFileId == null ? null : "/api/v1/files/%s".formatted(imageFileId);
	}
}
