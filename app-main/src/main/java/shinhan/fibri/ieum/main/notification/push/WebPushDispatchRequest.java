package shinhan.fibri.ieum.main.notification.push;

import com.interaso.webpush.WebPush;
import java.util.Arrays;
import java.util.Objects;

public record WebPushDispatchRequest(
	byte[] payload,
	int ttlSeconds,
	String topic,
	WebPush.Urgency urgency
) {

	private static final int MAX_TOPIC_LENGTH = 32;

	public WebPushDispatchRequest {
		payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
		if (ttlSeconds < 0) {
			throw new IllegalArgumentException("ttlSeconds must be zero or positive");
		}
		if (topic != null && (topic.isEmpty()
			|| topic.length() > MAX_TOPIC_LENGTH
			|| !topic.matches("[A-Za-z0-9_-]+"))) {
			throw new IllegalArgumentException("topic must be base64url-safe and at most 32 characters");
		}
		urgency = Objects.requireNonNull(urgency, "urgency");
	}

	@Override
	public byte[] payload() {
		return Arrays.copyOf(payload, payload.length);
	}

	@Override
	public String toString() {
		return "WebPushDispatchRequest[payload=<redacted>, ttlSeconds=%d, topic=%s, urgency=%s]"
			.formatted(ttlSeconds, topic, urgency);
	}
}
