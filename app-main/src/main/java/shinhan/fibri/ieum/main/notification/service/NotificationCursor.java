package shinhan.fibri.ieum.main.notification.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import shinhan.fibri.ieum.main.notification.exception.InvalidNotificationCursorException;

public record NotificationCursor(
	OffsetDateTime createdAt,
	Long notificationId
) {

	private static final long MICROS_PER_SECOND = 1_000_000L;

	public static String encode(OffsetDateTime createdAt, Long notificationId) {
		if (createdAt == null || notificationId == null) {
			return null;
		}
		long epochMicros = Math.addExact(
			Math.multiplyExact(createdAt.toEpochSecond(), MICROS_PER_SECOND),
			createdAt.getNano() / 1_000L
		);
		String raw = epochMicros + ":" + notificationId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static NotificationCursor decode(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String[] parts = raw.split(":", -1);
			if (parts.length != 2) {
				throw new IllegalArgumentException("Invalid cursor");
			}
			long epochMicros = Long.parseLong(parts[0]);
			long notificationId = Long.parseLong(parts[1]);
			if (notificationId < 1) {
				throw new IllegalArgumentException("Invalid cursor");
			}
			long epochSeconds = Math.floorDiv(epochMicros, MICROS_PER_SECOND);
			long micros = Math.floorMod(epochMicros, MICROS_PER_SECOND);
			OffsetDateTime createdAt = OffsetDateTime.ofInstant(
				Instant.ofEpochSecond(epochSeconds, micros * 1_000L),
				ZoneOffset.UTC
			);
			return new NotificationCursor(createdAt, notificationId);
		} catch (RuntimeException exception) {
			throw new InvalidNotificationCursorException();
		}
	}
}
