package shinhan.fibri.ieum.ai.question.repository;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record ClaimedQuestionTask(
	long questionId,
	String workerId,
	UUID leaseToken,
	OffsetDateTime leaseUntil,
	int attempts
) {

	public ClaimedQuestionTask {
		if (questionId < 1) {
			throw new IllegalArgumentException("questionId must be positive");
		}
		if (workerId == null || workerId.isBlank() || workerId.length() > 100) {
			throw new IllegalArgumentException("workerId must contain 1 to 100 characters");
		}
		workerId = workerId.trim();
		leaseToken = Objects.requireNonNull(leaseToken, "leaseToken must not be null");
		leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
		if (attempts < 1 || attempts > 5) {
			throw new IllegalArgumentException("attempts must be between 1 and 5");
		}
	}
}
