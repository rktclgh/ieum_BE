package shinhan.fibri.ieum.main.notification.presence;

import java.util.Objects;

public record UserPresenceChangedEvent(
	Long userId,
	boolean online
) {

	public UserPresenceChangedEvent {
		Objects.requireNonNull(userId, "userId must not be null");
	}
}
