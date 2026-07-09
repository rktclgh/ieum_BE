package shinhan.fibri.ieum.main.chat.service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import shinhan.fibri.ieum.common.chat.domain.Message;

public record ChatMessageCursor(
	OffsetDateTime createdAt,
	Long messageId
) {

	public static String encode(Message message) {
		return encode(message.getCreatedAt(), message.getId());
	}

	public static String encode(OffsetDateTime createdAt, Long messageId) {
		if (createdAt == null || messageId == null) {
			return null;
		}
		String raw = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(createdAt) + "|" + messageId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static ChatMessageCursor decode(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String[] parts = decoded.split("\\|", 2);
			if (parts.length != 2) {
				throw new IllegalArgumentException("Invalid cursor");
			}
			return new ChatMessageCursor(OffsetDateTime.parse(parts[0]), Long.parseLong(parts[1]));
		} catch (IllegalArgumentException | DateTimeParseException exception) {
			throw new IllegalArgumentException("Invalid cursor");
		}
	}
}
