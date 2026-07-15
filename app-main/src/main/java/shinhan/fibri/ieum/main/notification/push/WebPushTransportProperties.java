package shinhan.fibri.ieum.main.notification.push;

import com.interaso.webpush.VapidKeys;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public record WebPushTransportProperties(
	String vapidPrivateKey,
	String vapidSubject,
	Duration connectTimeout,
	Duration requestTimeout
) {

	public WebPushTransportProperties {
		vapidPrivateKey = vapidPrivateKey == null ? "" : vapidPrivateKey;
		vapidSubject = vapidSubject == null ? "" : vapidSubject;
		connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
		requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
		if (connectTimeout.isZero() || connectTimeout.isNegative()) {
			throw new IllegalStateException("app.web-push.connect-timeout must be positive");
		}
		if (requestTimeout.isZero() || requestTimeout.isNegative()) {
			throw new IllegalStateException("app.web-push.request-timeout must be positive");
		}
	}

	public VapidKeys createVapidKeys(String vapidPublicKey) {
		if (vapidPrivateKey.isBlank() || !vapidPrivateKey.equals(vapidPrivateKey.trim())) {
			throw new IllegalStateException("app.web-push.vapid-private-key must be configured");
		}
		if (!isValidVapidSubject(vapidSubject)) {
			throw new IllegalStateException(
				"app.web-push.vapid-subject must be a valid mailto: or https: contact URI"
			);
		}
		try {
			return VapidKeys.fromUncompressedBytes(vapidPublicKey, vapidPrivateKey);
		}
		catch (RuntimeException exception) {
			throw new IllegalStateException(
				"app.web-push.vapid-public-key and app.web-push.vapid-private-key must contain a matching raw P-256 VAPID key pair"
			);
		}
	}

	private static boolean isValidVapidSubject(String value) {
		if (value == null || value.isBlank() || !value.equals(value.trim())) {
			return false;
		}
		try {
			URI uri = URI.create(value);
			if ("mailto".equalsIgnoreCase(uri.getScheme())) {
				return uri.getSchemeSpecificPart() != null && !uri.getSchemeSpecificPart().isBlank();
			}
			return "https".equalsIgnoreCase(uri.getScheme())
				&& uri.getHost() != null
				&& uri.getUserInfo() == null;
		}
		catch (IllegalArgumentException exception) {
			return false;
		}
	}

	@Override
	public String toString() {
		return "WebPushTransportProperties[vapidPrivateKey=<redacted>, vapidSubject=%s, connectTimeout=%s, requestTimeout=%s]"
			.formatted(vapidSubject, connectTimeout, requestTimeout);
	}
}
