package shinhan.fibri.ieum.ai.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.report")
public record ReportReviewProperties(
	long imageMaxBytes,
	long imageMaxTotalBytes,
	Set<String> imageAllowedHosts,
	Duration imageDownloadTimeout
) {
	private static final long MAX_IMAGE_BYTES = 3_750_000L;
	private static final long MAX_TOTAL_BYTES = 15_000_000L;

	public ReportReviewProperties {
		if (imageMaxBytes < 12 || imageMaxBytes > MAX_IMAGE_BYTES) {
			throw new IllegalArgumentException("imageMaxBytes must be between 12 and " + MAX_IMAGE_BYTES);
		}
		if (imageMaxTotalBytes < imageMaxBytes || imageMaxTotalBytes > MAX_TOTAL_BYTES) {
			throw new IllegalArgumentException("imageMaxTotalBytes must be between imageMaxBytes and " + MAX_TOTAL_BYTES);
		}
		if (imageDownloadTimeout == null || imageDownloadTimeout.isZero() || imageDownloadTimeout.isNegative()) {
			throw new IllegalArgumentException("imageDownloadTimeout must be positive");
		}
		if (imageAllowedHosts == null) {
			throw new IllegalArgumentException("imageAllowedHosts must not be empty");
		}
		imageAllowedHosts = imageAllowedHosts.stream()
			.filter(host -> host != null && !host.isBlank())
			.map(String::trim)
			.map(host -> host.toLowerCase(Locale.ROOT))
			.collect(Collectors.toUnmodifiableSet());
		if (imageAllowedHosts.isEmpty()) {
			throw new IllegalArgumentException("imageAllowedHosts must not be empty");
		}
	}
}
