package shinhan.fibri.ieum.main.notification.sse;

import java.util.Objects;

public record PresenceSsePayload(
	Long userId,
	boolean online
) implements SseEventPayload {

	public PresenceSsePayload {
		Objects.requireNonNull(userId, "userId must not be null");
	}
}
