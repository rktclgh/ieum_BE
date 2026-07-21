package shinhan.fibri.ieum.main.chat.service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import shinhan.fibri.ieum.common.chat.domain.ChatNotice;

public record ChatNoticeCursor(
	OffsetDateTime createdAt,
	Long noticeId
) {

	public static String encode(ChatNotice notice) {
		return encode(notice.getCreatedAt(), notice.getId());
	}

	public static String encode(OffsetDateTime createdAt, Long noticeId) {
		if (createdAt == null || noticeId == null) {
			return null;
		}
		String raw = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(createdAt) + "|" + noticeId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static ChatNoticeCursor decode(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String[] parts = decoded.split("\\|", 2);
			if (parts.length != 2) {
				throw new IllegalArgumentException("Invalid cursor");
			}
			return new ChatNoticeCursor(OffsetDateTime.parse(parts[0]), Long.parseLong(parts[1]));
		} catch (IllegalArgumentException | DateTimeParseException exception) {
			throw new IllegalArgumentException("Invalid cursor");
		}
	}
}
