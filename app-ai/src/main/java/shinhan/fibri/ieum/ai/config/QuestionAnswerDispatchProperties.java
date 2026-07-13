package shinhan.fibri.ieum.ai.config;

import java.time.Duration;

public record QuestionAnswerDispatchProperties(
	boolean enabled,
	Duration taskLease,
	int maxAttempts,
	Duration recoveryInterval,
	int recoveryBatchSize,
	int retryAfterSeconds
) {

	private static final int MAX_ATTEMPTS = 5;
	private static final int MAX_RECOVERY_BATCH = 32;
	private static final Duration MIN_RECOVERY_INTERVAL = Duration.ofSeconds(60);

	public QuestionAnswerDispatchProperties {
		if (taskLease == null || taskLease.compareTo(Duration.ofSeconds(1)) < 0) {
			throw new IllegalArgumentException("task lease must be at least one second");
		}
		if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS) {
			throw new IllegalArgumentException("max attempts must be between 1 and " + MAX_ATTEMPTS);
		}
		if (recoveryInterval == null || recoveryInterval.compareTo(MIN_RECOVERY_INTERVAL) < 0) {
			throw new IllegalArgumentException("recovery interval must be at least 60 seconds");
		}
		if (recoveryBatchSize < 1 || recoveryBatchSize > MAX_RECOVERY_BATCH) {
			throw new IllegalArgumentException("recovery batch must be between 1 and " + MAX_RECOVERY_BATCH);
		}
		if (retryAfterSeconds < 1) {
			throw new IllegalArgumentException("retry-after seconds must be positive");
		}
	}
}
