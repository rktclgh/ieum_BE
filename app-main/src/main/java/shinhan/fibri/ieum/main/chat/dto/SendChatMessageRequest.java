package shinhan.fibri.ieum.main.chat.dto;

import java.util.UUID;

public record SendChatMessageRequest(
	String content,
	UUID imageFileId
) {
}
