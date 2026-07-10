package shinhan.fibri.ieum.main.notification.sse;

import java.util.Objects;

public record OutboundEvent(
	Kind kind,
	NotificationSsePayload payload
) {

	public enum Kind {
		durable,
		ephemeral,
		heartbeat
	}

	public OutboundEvent {
		Objects.requireNonNull(kind, "kind must not be null");
		if (kind == Kind.heartbeat && payload != null) {
			throw new IllegalArgumentException("heartbeat must not have payload");
		}
		if (kind == Kind.durable && (payload == null || !payload.persistent())) {
			throw new IllegalArgumentException("durable event requires persistent payload");
		}
		if (kind == Kind.ephemeral && (payload == null || payload.persistent())) {
			throw new IllegalArgumentException("ephemeral event requires non-persistent payload");
		}
	}

	public static OutboundEvent durable(NotificationSsePayload payload) {
		return new OutboundEvent(Kind.durable, payload);
	}

	public static OutboundEvent ephemeral(NotificationSsePayload payload) {
		return new OutboundEvent(Kind.ephemeral, payload);
	}

	public static OutboundEvent heartbeat() {
		return new OutboundEvent(Kind.heartbeat, null);
	}
}
