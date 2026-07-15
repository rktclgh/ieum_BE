package shinhan.fibri.ieum.main.report.ai.service;

import java.time.Duration;

public record ReportAiWorkerProperties(String workerId, Duration lease, int maxAttempts, int batchSize) {

	public ReportAiWorkerProperties {
		if (workerId == null || workerId.isBlank() || workerId.length() > 120) {
			throw new IllegalArgumentException("workerId must contain 1 to 120 characters");
		}
		if (lease == null || lease.isZero() || lease.isNegative()) {
			throw new IllegalArgumentException("lease must be positive");
		}
		if (maxAttempts < 1 || maxAttempts > 5) {
			throw new IllegalArgumentException("maxAttempts must be between 1 and 5");
		}
		if (batchSize < 1 || batchSize > 32) {
			throw new IllegalArgumentException("batchSize must be between 1 and 32");
		}
	}
}
