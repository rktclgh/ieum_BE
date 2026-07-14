package shinhan.fibri.ieum.ai.question.finalization;

import java.util.Objects;
import java.util.UUID;

public record QuestionTaskFence(long questionId, String workerId, UUID leaseToken) {

	public QuestionTaskFence {
		if (questionId < 1) {
			throw new IllegalArgumentException("questionId must be positive");
		}
		if (workerId == null || workerId.isBlank() || workerId.length() > 100) {
			throw new IllegalArgumentException("workerId must contain 1 to 100 characters");
		}
		workerId = workerId.trim();
		leaseToken = Objects.requireNonNull(leaseToken, "leaseToken must not be null");
	}
}
