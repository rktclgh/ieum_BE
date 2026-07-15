package shinhan.fibri.ieum.main.notification.push;

import com.interaso.webpush.WebPush;
import java.net.URI;
import java.util.Arrays;
import java.util.Objects;

public record WebPushProviderRequest(
	byte[] payload,
	String endpoint,
	String p256dh,
	String auth,
	int ttlSeconds,
	String topic,
	WebPush.Urgency urgency
) {

	private static final int MAX_TOPIC_LENGTH = 32;

	public WebPushProviderRequest {
		payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
		endpoint = requireHttpsEndpoint(endpoint);
		p256dh = requireSecret(p256dh, "p256dh");
		auth = requireSecret(auth, "auth");
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
		return "WebPushProviderRequest[payload=<redacted>, endpoint=<redacted>, p256dh=<redacted>, auth=<redacted>, ttlSeconds=%d, topic=%s, urgency=%s]"
			.formatted(ttlSeconds, topic, urgency);
	}

	private static String requireHttpsEndpoint(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("endpoint must be an absolute HTTPS URI");
		}
		try {
			URI uri = URI.create(value);
			if (!"https".equalsIgnoreCase(uri.getScheme())
				|| uri.getHost() == null
				|| uri.getUserInfo() != null
				|| uri.getRawFragment() != null
				|| (uri.getPort() != -1 && uri.getPort() != 443)) {
				throw new IllegalArgumentException("endpoint must be an absolute HTTPS URI");
			}
			return value;
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("endpoint must be an absolute HTTPS URI");
		}
	}

	private static String requireSecret(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must be configured");
		}
		return value;
	}
}
