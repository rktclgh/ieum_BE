package shinhan.fibri.ieum.main.notification.sse;

import java.util.Objects;

public record OutboundEvent(
	Kind kind,
	String eventName,
	SseEventPayload payload
) {

	public enum Kind {
		durable,
		ephemeral,
		heartbeat
	}

	public OutboundEvent {
		Objects.requireNonNull(kind, "kind must not be null");
		if (kind == Kind.heartbeat) {
			if (eventName != null || payload != null) {
				throw new IllegalArgumentException("heartbeat must not have eventName or payload");
			}
		} else {
			Objects.requireNonNull(eventName, "eventName must not be null");
			if (payload instanceof NotificationSsePayload && !"notification".equals(eventName)) {
				throw new IllegalArgumentException("notification payload requires notification event name");
			}
			if (payload instanceof PresenceSsePayload && !"presence".equals(eventName)) {
				throw new IllegalArgumentException("presence payload requires presence event name");
			}
			if (kind == Kind.durable && !isPersistentNotification(payload)) {
				throw new IllegalArgumentException("durable event requires persistent payload");
			}
			if (kind == Kind.ephemeral && !isEphemeralPayload(payload)) {
				throw new IllegalArgumentException("ephemeral event requires non-persistent payload");
			}
		}
	}

	public static OutboundEvent durable(NotificationSsePayload payload) {
		return new OutboundEvent(Kind.durable, "notification", payload);
	}

	public static OutboundEvent ephemeral(NotificationSsePayload payload) {
		return new OutboundEvent(Kind.ephemeral, "notification", payload);
	}

	public static OutboundEvent presence(PresenceSsePayload payload) {
		return new OutboundEvent(Kind.ephemeral, "presence", payload);
	}

	public static OutboundEvent heartbeat() {
		return new OutboundEvent(Kind.heartbeat, null, null);
	}

	public NotificationSsePayload notificationPayload() {
		return payload instanceof NotificationSsePayload notificationPayload ? notificationPayload : null;
	}

	private static boolean isPersistentNotification(SseEventPayload payload) {
		return payload instanceof NotificationSsePayload notificationPayload && notificationPayload.persistent();
	}

	private static boolean isEphemeralPayload(SseEventPayload payload) {
		return payload instanceof PresenceSsePayload
			|| payload instanceof NotificationSsePayload notificationPayload && !notificationPayload.persistent();
	}
}
