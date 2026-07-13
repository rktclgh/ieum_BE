package shinhan.fibri.ieum.ai.question.service;

import java.util.Optional;
import shinhan.fibri.ieum.ai.question.repository.QuestionTaskDispatchSnapshot;
import shinhan.fibri.ieum.ai.question.repository.QuestionTaskStatus;
import shinhan.fibri.ieum.ai.question.repository.QuestionTaskWorkRepository;

public class QuestionAnswerJobDispatchService {

	private final QuestionTaskWorkRepository repository;
	private final QuestionAnswerTaskLane lane;
	private final QuestionCompletionCallbackWake callbackWake;

	public QuestionAnswerJobDispatchService(
		QuestionTaskWorkRepository repository,
		QuestionAnswerTaskLane lane,
		QuestionCompletionCallbackWake callbackWake
	) {
		this.repository = repository;
		this.lane = lane;
		this.callbackWake = callbackWake;
	}

	public QuestionAnswerJobDispatchResult dispatch(long questionId) {
		if (questionId < 1) {
			return QuestionAnswerJobDispatchResult.INVARIANT_BREACH;
		}
		Optional<QuestionTaskDispatchSnapshot> found = repository.findDispatchSnapshot(questionId);
		if (found.isEmpty()) {
			return QuestionAnswerJobDispatchResult.INVARIANT_BREACH;
		}
		QuestionTaskDispatchSnapshot task = found.get();
		if (task.status() == QuestionTaskStatus.COMPLETED) {
			if (task.hasPendingAnswerCallback()) {
				callbackWake.wake(questionId);
			}
			return QuestionAnswerJobDispatchResult.ALREADY_COMPLETED;
		}
		if (task.isCancelledOrDeleted()) {
			return QuestionAnswerJobDispatchResult.CANCELLED_OR_DELETED;
		}
		if (task.status() == QuestionTaskStatus.DEAD) {
			return QuestionAnswerJobDispatchResult.DEAD;
		}
		if (task.activeLease()) {
			return QuestionAnswerJobDispatchResult.ALREADY_ACTIVE;
		}
		return switch (lane.submit(questionId)) {
			case ENQUEUED -> QuestionAnswerJobDispatchResult.ENQUEUED;
			case ALREADY_ACTIVE -> QuestionAnswerJobDispatchResult.ALREADY_ACTIVE;
			case SATURATED -> QuestionAnswerJobDispatchResult.SATURATED;
			case DISABLED -> QuestionAnswerJobDispatchResult.DISABLED;
		};
	}
}
