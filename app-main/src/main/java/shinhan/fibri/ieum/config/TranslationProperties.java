package shinhan.fibri.ieum.config;

import java.time.Duration;
import java.util.Objects;

public record TranslationProperties(
	String apiKey,
	Duration connectTimeout,
	Duration readTimeout
) {

	public TranslationProperties {
		apiKey = apiKey == null ? "" : apiKey.trim();
		connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
		readTimeout = Objects.requireNonNull(readTimeout, "readTimeout must not be null");
		if (connectTimeout.isZero() || connectTimeout.isNegative()) {
			throw new IllegalArgumentException("connectTimeout must be positive");
		}
		if (readTimeout.isZero() || readTimeout.isNegative()) {
			throw new IllegalArgumentException("readTimeout must be positive");
		}
	}
}
