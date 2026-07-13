package shinhan.fibri.ieum.ai.question.service;

import shinhan.fibri.ieum.ai.question.repository.QuestionTaskWorkRepository;

public class QuestionTaskRecoveryService {

	private final QuestionTaskWorkRepository repository;
	private final QuestionAnswerTaskLane lane;
	private final int maxAttempts;
	private final int batchSize;

	public QuestionTaskRecoveryService(
		QuestionTaskWorkRepository repository,
		QuestionAnswerTaskLane lane,
		int maxAttempts,
		int batchSize
	) {
		this.repository = repository;
		this.lane = lane;
		this.maxAttempts = maxAttempts;
		this.batchSize = batchSize;
	}

	public void recover() {
		if (!lane.isEnabled()) {
			return;
		}
		repository.cleanupCancelledOrDeleted(batchSize);
		repository.markExhaustedDueTasksDead(maxAttempts, batchSize);
		for (Long questionId : repository.findDueQuestionIds(maxAttempts, batchSize)) {
			QuestionAnswerTaskSubmission result = lane.submit(questionId);
			if (result == QuestionAnswerTaskSubmission.SATURATED
					|| result == QuestionAnswerTaskSubmission.DISABLED) {
				break;
			}
		}
	}
}
