package shinhan.fibri.ieum.main.notification.push;

import java.net.URI;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public class WebPushSubscriptionValidator {

	private static final int MAX_ENDPOINT_LENGTH = 2048;
	private static final int MAX_P256DH_LENGTH = 512;
	private static final int MAX_AUTH_SECRET_LENGTH = 256;
	private static final int P256DH_LENGTH = 65;
	private static final int AUTH_SECRET_LENGTH = 16;
	private static final long MAX_EXPIRATION_EPOCH_MILLIS = 253_402_300_799_999L;

	private final Set<String> allowedEndpointHosts;
	private final Clock clock;

	public WebPushSubscriptionValidator(Set<String> allowedEndpointHosts, Clock clock) {
		this.allowedEndpointHosts = Set.copyOf(Objects.requireNonNull(
			allowedEndpointHosts,
			"allowedEndpointHosts must not be null"
		));
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	public WebPushSubscriptionInput validate(
		long userId,
		String sessionId,
		WebPushSubscriptionRequest request
	) {
		if (request == null) {
			throw invalid("request", "Request body is required");
		}
		String endpoint = validateEndpoint(request.endpoint());
		WebPushSubscriptionRequest.Keys keys = request.keys();
		if (keys == null) {
			throw invalid("keys", "Push encryption keys are required");
		}
		validateP256dh(keys.p256dh());
		validateAuthSecret(keys.auth());
		OffsetDateTime expiresAt = validateExpiration(request.expirationTime());
		return new WebPushSubscriptionInput(
			userId,
			sessionId,
			endpoint,
			keys.p256dh(),
			keys.auth(),
			expiresAt
		);
	}

	private String validateEndpoint(String endpoint) {
		if (endpoint == null || endpoint.isBlank() || endpoint.length() > MAX_ENDPOINT_LENGTH
				|| !endpoint.equals(endpoint.trim())) {
			throw invalid("endpoint", "Invalid push endpoint");
		}
		URI uri;
		try {
			uri = URI.create(endpoint);
		}
		catch (IllegalArgumentException exception) {
			throw invalid("endpoint", "Invalid push endpoint");
		}
		String host = uri.getHost();
		if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
				|| host == null || host.isBlank()
				|| uri.getUserInfo() != null || uri.getRawFragment() != null
				|| (uri.getPort() != -1 && uri.getPort() != 443)) {
			throw invalid("endpoint", "Invalid push endpoint");
		}
		String normalizedHost = host.toLowerCase(Locale.ROOT);
		boolean allowed = allowedEndpointHosts.stream()
			.anyMatch(suffix -> normalizedHost.equals(suffix) || normalizedHost.endsWith("." + suffix));
		if (!allowed) {
			throw invalid("endpoint", "Push endpoint host is not allowed");
		}
		return endpoint;
	}

	private void validateP256dh(String value) {
		byte[] decoded = decode(value, "keys.p256dh", MAX_P256DH_LENGTH);
		if (decoded.length != P256DH_LENGTH || decoded[0] != 0x04) {
			throw invalid("keys.p256dh", "Invalid p256dh key");
		}
	}

	private void validateAuthSecret(String value) {
		byte[] decoded = decode(value, "keys.auth", MAX_AUTH_SECRET_LENGTH);
		if (decoded.length != AUTH_SECRET_LENGTH) {
			throw invalid("keys.auth", "Invalid auth key");
		}
	}

	private byte[] decode(String value, String field, int maxLength) {
		if (value == null || value.isBlank()) {
			throw invalid(field, "Invalid push encryption key");
		}
		if (value.length() > maxLength) {
			throw invalid(field, "Push encryption key is too long");
		}
		try {
			return Base64.getUrlDecoder().decode(value);
		}
		catch (IllegalArgumentException exception) {
			throw invalid(field, "Invalid push encryption key");
		}
	}

	private OffsetDateTime validateExpiration(Long expirationTime) {
		if (expirationTime == null) {
			return null;
		}
		if (expirationTime > MAX_EXPIRATION_EPOCH_MILLIS) {
			throw invalid("expirationTime", "Expiration time is too far in the future");
		}
		Instant expiration;
		try {
			expiration = Instant.ofEpochMilli(expirationTime);
		}
		catch (DateTimeException exception) {
			throw invalid("expirationTime", "Invalid expiration time");
		}
		if (!expiration.isAfter(clock.instant())) {
			throw invalid("expirationTime", "Expiration time must be in the future");
		}
		return OffsetDateTime.ofInstant(expiration, ZoneOffset.UTC);
	}

	private InvalidWebPushSubscriptionException invalid(String field, String message) {
		return new InvalidWebPushSubscriptionException(field, message);
	}
}
