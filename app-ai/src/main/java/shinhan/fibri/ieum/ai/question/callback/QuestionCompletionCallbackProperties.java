package shinhan.fibri.ieum.ai.question.callback;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public record QuestionCompletionCallbackProperties(
	URI baseOrigin,
	Set<String> allowedOrigins,
	String internalToken,
	Duration connectTimeout,
	Duration readTimeout,
	Duration recoveryInterval
) {
	private static final Duration MIN_RECOVERY_INTERVAL = Duration.ofSeconds(10);

	public QuestionCompletionCallbackProperties {
		if (baseOrigin == null || allowedOrigins == null || allowedOrigins.isEmpty()) {
			throw new IllegalArgumentException("Callback origin and allowlist are required");
		}
		allowedOrigins = Set.copyOf(allowedOrigins);
		if (!allowedOrigins.contains(baseOrigin.toString())) {
			throw new IllegalArgumentException("Callback base origin must be present in the allowlist");
		}
		if (internalToken == null || internalToken.isBlank()) {
			throw new IllegalArgumentException("Callback token must not be blank");
		}
		if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
			|| readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
			throw new IllegalArgumentException("Callback timeouts must be positive");
		}
		if (recoveryInterval == null || recoveryInterval.compareTo(MIN_RECOVERY_INTERVAL) < 0) {
			throw new IllegalArgumentException("Callback recovery interval must be at least 10 seconds");
		}
	}

	public static QuestionCompletionCallbackProperties create(
		String baseOrigin,
		String allowedOrigins,
		String internalToken,
		Duration connectTimeout,
		Duration readTimeout
	) {
		return create(
			baseOrigin,
			allowedOrigins,
			internalToken,
			connectTimeout,
			readTimeout,
			Duration.ofSeconds(60)
		);
	}

	public static QuestionCompletionCallbackProperties create(
		String baseOrigin,
		String allowedOrigins,
		String internalToken,
		Duration connectTimeout,
		Duration readTimeout,
		Duration recoveryInterval
	) {
		URI normalizedBaseOrigin = normalizeOrigin(baseOrigin);
		Set<String> normalizedAllowlist = Arrays.stream(valueOrEmpty(allowedOrigins).split(","))
			.map(String::trim)
			.filter(value -> !value.isEmpty())
			.map(QuestionCompletionCallbackProperties::normalizeOrigin)
			.map(URI::toString)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		return new QuestionCompletionCallbackProperties(
			normalizedBaseOrigin,
			normalizedAllowlist,
			internalToken,
			connectTimeout,
			readTimeout,
			recoveryInterval
		);
	}

	private static URI normalizeOrigin(String raw) {
		URI uri;
		try {
			uri = URI.create(valueOrEmpty(raw).trim());
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Callback origin is invalid", exception);
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!(scheme.equals("http") || scheme.equals("https"))) {
			throw new IllegalArgumentException("Callback origin must use HTTP or HTTPS");
		}
		if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
			|| !(uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
			throw new IllegalArgumentException("Callback origin must be an HTTP(S) origin without a path");
		}
		try {
			return new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), null, null, null);
		}
		catch (java.net.URISyntaxException exception) {
			throw new IllegalArgumentException("Callback origin is invalid", exception);
		}
	}

	private static String valueOrEmpty(String value) {
		return value == null ? "" : value;
	}
}
