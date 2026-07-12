package shinhan.fibri.ieum.config;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public record AiServiceProperties(
	URI baseUri,
	Set<String> allowedHosts,
	Duration connectTimeout,
	Duration readTimeout
) {

	public AiServiceProperties(String baseUrl, String allowedHosts, Duration connectTimeout, Duration readTimeout) {
		this(parseBaseUri(baseUrl), parseAllowedHosts(allowedHosts), connectTimeout, readTimeout);
	}

	public AiServiceProperties {
		if (!allowedHosts.contains(baseUri.getHost().toLowerCase(Locale.ROOT))) {
			throw new IllegalStateException("app.ai.report.base-url host must appear in app.ai.report.allowed-hosts");
		}
		allowedHosts = Set.copyOf(allowedHosts);
		if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
			throw new IllegalStateException("app.ai.report.connect-timeout-seconds must be positive");
		}
		if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
			throw new IllegalStateException("app.ai.report.read-timeout-seconds must be positive");
		}
	}

	private static Set<String> parseAllowedHosts(String value) {
		requireNonBlank(value, "app.ai.report.allowed-hosts");
		Set<String> hosts = Arrays.stream(value.split(","))
			.map(String::trim)
			.filter(host -> !host.isBlank())
			.map(host -> host.toLowerCase(Locale.ROOT))
			.collect(Collectors.toUnmodifiableSet());
		if (hosts.isEmpty()) {
			throw new IllegalStateException("app.ai.report.allowed-hosts must be configured");
		}
		return hosts;
	}

	private static URI parseBaseUri(String value) {
		requireNonBlank(value, "app.ai.report.base-url");
		try {
			URI uri = URI.create(value);
			if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()
					|| uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
					|| !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
				throw new IllegalStateException("app.ai.report.base-url must be an origin URL without user info, path, query, or fragment");
			}
			if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
				return uri;
			}
			throw new IllegalStateException("app.ai.report.base-url must use HTTP or HTTPS");
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalStateException("app.ai.report.base-url must be a valid origin URL", exception);
		}
	}

	private static void requireNonBlank(String value, String propertyName) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(propertyName + " must be configured");
		}
	}

}
