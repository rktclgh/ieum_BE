package shinhan.fibri.ieum.main.notification.push;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class WebPushEndpointPolicy {

	private static final int MAX_ENDPOINT_LENGTH = 2048;

	private final Set<String> allowedEndpointHosts;

	public WebPushEndpointPolicy(Set<String> allowedEndpointHosts) {
		this.allowedEndpointHosts = Objects.requireNonNull(
			allowedEndpointHosts,
			"allowedEndpointHosts must not be null"
		).stream()
			.map(host -> host.toLowerCase(Locale.ROOT))
			.collect(Collectors.toUnmodifiableSet());
	}

	public URI validate(String endpoint) {
		if (endpoint == null || endpoint.isBlank() || endpoint.length() > MAX_ENDPOINT_LENGTH
				|| !endpoint.equals(endpoint.trim())) {
			throw invalid();
		}

		URI uri;
		try {
			uri = URI.create(endpoint);
		}
		catch (IllegalArgumentException exception) {
			throw invalid();
		}

		String host = uri.getHost();
		if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
				|| host == null || host.isBlank()
				|| uri.getUserInfo() != null || uri.getRawFragment() != null
				|| (uri.getPort() != -1 && uri.getPort() != 443)) {
			throw invalid();
		}

		String normalizedHost = host.toLowerCase(Locale.ROOT);
		boolean allowed = allowedEndpointHosts.stream()
			.anyMatch(suffix -> normalizedHost.equals(suffix) || normalizedHost.endsWith("." + suffix));
		if (!allowed) {
			throw new IllegalArgumentException("Web Push endpoint host is not allowed");
		}
		return uri;
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("Invalid Web Push endpoint");
	}
}
