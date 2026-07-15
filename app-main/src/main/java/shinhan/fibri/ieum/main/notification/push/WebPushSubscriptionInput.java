package shinhan.fibri.ieum.main.notification.push;

import java.time.OffsetDateTime;

public record WebPushSubscriptionInput(
	long userId,
	String sessionId,
	String endpoint,
	String p256dh,
	String authSecret,
	OffsetDateTime expiresAt
) {

	public WebPushSubscriptionInput {
		if (userId < 1) {
			throw new IllegalArgumentException("userId must be positive");
		}
		requireText(sessionId, 64, "sessionId");
		requireText(endpoint, Integer.MAX_VALUE, "endpoint");
		requireText(p256dh, 512, "p256dh");
		requireText(authSecret, 256, "authSecret");
	}

	private static void requireText(String value, int maxLength, String fieldName) {
		if (value == null || value.isBlank() || value.length() > maxLength) {
			throw new IllegalArgumentException(fieldName + " must contain between 1 and " + maxLength + " characters");
		}
	}
}
