package shinhan.fibri.ieum.ai.question.repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface QuestionTaskWorkRepository {

	Optional<ClaimedQuestionTask> claimNext(String workerId, Duration lease, int maxAttempts);

	boolean markRetry(
		long questionId,
		String workerId,
		UUID leaseToken,
		OffsetDateTime nextAttemptAt,
		String errorCode,
		String errorMessage
	);
}
