package shinhan.fibri.ieum.main.file.service;

import java.time.Duration;

public record FileProperties(
	String tmpPrefix,
	String finalPrefix,
	Duration presignTtl,
	Long maxSizeBytes,
	int displayMaxPx,
	int thumbMaxPx,
	int webpQuality
) {
}
