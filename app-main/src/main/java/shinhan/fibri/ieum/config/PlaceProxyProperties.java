package shinhan.fibri.ieum.config;

import java.net.URI;
import java.util.Set;

public record PlaceProxyProperties(
	String naverSearchBaseUrl,
	String naverSearchClientId,
	String naverSearchClientSecret,
	String ncpMapsBaseUrl,
	String ncpMapsKeyId,
	String ncpMapsKey,
	int connectTimeoutMs,
	int readTimeoutMs,
	int maxConcurrent
) {
	private static final Set<String> ALLOWED_HOSTS = Set.of(
		"openapi.naver.com", "naverapihub.apigw.ntruss.com", "maps.apigw.ntruss.com", "localhost", "127.0.0.1"
	);

	public PlaceProxyProperties {
		requireNonBlank(naverSearchBaseUrl, "naverSearchBaseUrl");
		requireNonBlank(naverSearchClientId, "naverSearchClientId");
		requireNonBlank(naverSearchClientSecret, "naverSearchClientSecret");
		requireNonBlank(ncpMapsBaseUrl, "ncpMapsBaseUrl");
		requireNonBlank(ncpMapsKeyId, "ncpMapsKeyId");
		requireNonBlank(ncpMapsKey, "ncpMapsKey");
		requireAllowedHost(naverSearchBaseUrl, "naverSearchBaseUrl");
		requireAllowedHost(ncpMapsBaseUrl, "ncpMapsBaseUrl");
		if (connectTimeoutMs < 1 || readTimeoutMs < 1 || maxConcurrent < 1) {
			throw new IllegalStateException("Place proxy timeout and concurrency values must be positive");
		}
	}

	private static void requireNonBlank(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " must not be blank");
		}
	}

	private static void requireAllowedHost(String value, String name) {
		try {
			String host = URI.create(value).getHost();
			if (host == null || !ALLOWED_HOSTS.contains(host)) {
				throw new IllegalStateException(name + " must use an allowed host");
			}
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException(name + " must use an allowed host", exception);
		}
	}
}
