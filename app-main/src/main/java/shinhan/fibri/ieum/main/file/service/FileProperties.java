package shinhan.fibri.ieum.main.file.service;

import java.time.Duration;

public record FileProperties(
	String tmpPrefix,
	String finalPrefix,
	Duration presignTtl,
	Long maxSizeBytes,
	long maxSourcePixels,
	int maxSourceDimension,
	int displayMaxPx,
	int thumbMaxPx,
	int webpQuality
) {
}
