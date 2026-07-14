package shinhan.fibri.ieum.ai.config;

import java.time.Duration;

public record QuestionAnswerDispatchProperties(
	boolean enabled,
	Duration taskLease,
	int maxAttempts,
	int retryAfterSeconds
) {

	private static final int MAX_ATTEMPTS = 5;

	public QuestionAnswerDispatchProperties {
		if (taskLease == null || taskLease.compareTo(Duration.ofSeconds(1)) < 0) {
			throw new IllegalArgumentException("task lease must be at least one second");
		}
		if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS) {
			throw new IllegalArgumentException("max attempts must be between 1 and " + MAX_ATTEMPTS);
		}
		if (retryAfterSeconds < 1) {
			throw new IllegalArgumentException("retry-after seconds must be positive");
		}
	}
}
