package shinhan.fibri.ieum.ai.question.repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import shinhan.fibri.ieum.ai.question.service.QuestionTaskFailure;

public interface QuestionTaskWorkRepository {

	Optional<ClaimedQuestionTask> claimByQuestionId(
		long questionId,
		String workerId,
		Duration lease,
		int maxAttempts
	);

	Optional<QuestionTaskDispatchSnapshot> findDispatchSnapshot(long questionId);

	List<Long> findDueQuestionIds(int maxAttempts, int limit);

	int cleanupCancelledOrDeleted(int limit);

	int markExhaustedDueTasksDead(int maxAttempts, int limit);

	boolean markRetry(
		long questionId,
		String workerId,
		UUID leaseToken,
		Duration retryDelay,
		QuestionTaskFailure failure
	);

	boolean markDead(
		long questionId,
		String workerId,
		UUID leaseToken,
		QuestionTaskFailure failure
	);
}
