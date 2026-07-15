package shinhan.fibri.ieum.main.notification.push;

import java.time.OffsetDateTime;
import java.util.Objects;

public record WebPushSubscription(
	long subscriptionId,
	long userId,
	String sessionId,
	String endpoint,
	String p256dh,
	String authSecret,
	long bindingVersion,
	OffsetDateTime expiresAt,
	OffsetDateTime createdAt,
	OffsetDateTime updatedAt
) {

	public WebPushSubscription {
		if (subscriptionId < 1 || userId < 1 || bindingVersion < 1) {
			throw new IllegalArgumentException("subscriptionId, userId, and bindingVersion must be positive");
		}
		Objects.requireNonNull(sessionId, "sessionId must not be null");
		Objects.requireNonNull(endpoint, "endpoint must not be null");
		Objects.requireNonNull(p256dh, "p256dh must not be null");
		Objects.requireNonNull(authSecret, "authSecret must not be null");
		Objects.requireNonNull(createdAt, "createdAt must not be null");
		Objects.requireNonNull(updatedAt, "updatedAt must not be null");
	}

	@Override
	public String toString() {
		return "WebPushSubscription[subscriptionId=%d, userId=%d, bindingVersion=%d, expiresAt=%s, createdAt=%s, updatedAt=%s]"
			.formatted(subscriptionId, userId, bindingVersion, expiresAt, createdAt, updatedAt);
	}
}
