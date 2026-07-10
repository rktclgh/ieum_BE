package shinhan.fibri.ieum.main.notification.sse;

import java.time.OffsetDateTime;
import java.util.Objects;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;

public record NotificationSsePayload(
	Long notificationId,
	NotificationType type,
	String title,
	String body,
	Long refId,
	OffsetDateTime createdAt,
	boolean persistent
) {

	public NotificationSsePayload {
		Objects.requireNonNull(type, "type must not be null");
		Objects.requireNonNull(title, "title must not be null");
		Objects.requireNonNull(createdAt, "createdAt must not be null");
		if (persistent && notificationId == null) {
			throw new IllegalArgumentException("persistent notification requires notificationId");
		}
		if (!persistent && notificationId != null) {
			throw new IllegalArgumentException("ephemeral notification must not have notificationId");
		}
	}

	public static NotificationSsePayload durable(
		Long notificationId,
		NotificationType type,
		String title,
		String body,
		Long refId,
		OffsetDateTime createdAt
	) {
		return new NotificationSsePayload(notificationId, type, title, body, refId, createdAt, true);
	}

	public static NotificationSsePayload ephemeral(
		NotificationType type,
		String title,
		String body,
		Long refId,
		OffsetDateTime createdAt
	) {
		return new NotificationSsePayload(null, type, title, body, refId, createdAt, false);
	}
}
