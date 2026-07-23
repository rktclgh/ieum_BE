package shinhan.fibri.ieum.main.admin.content.service;

import java.time.Duration;

public record FileCleanupTaskProperties(String workerId, Duration lease, int maxAttempts, int batchSize) {

	public FileCleanupTaskProperties {
		if (workerId == null || workerId.isBlank() || workerId.length() > 120) {
			throw new IllegalArgumentException("workerId must contain 1 to 120 characters");
		}
		if (lease == null || lease.isZero() || lease.isNegative() || lease.toSeconds() < 1) {
			throw new IllegalArgumentException("lease must be at least one second");
		}
		if (maxAttempts < 1 || maxAttempts > 20) {
			throw new IllegalArgumentException("maxAttempts must be between 1 and 20");
		}
		if (batchSize < 1 || batchSize > 200) {
			throw new IllegalArgumentException("batchSize must be between 1 and 200");
		}
	}
}
