package shinhan.fibri.ieum.ai.question.callback;

import shinhan.fibri.ieum.ai.question.service.QuestionCompletionCallbackWake;

public class QuestionCompletionCallbackRecoveryService {

	private static final int RECOVERY_BATCH_SIZE = 32;

	private final QuestionCompletionCallbackRepository repository;
	private final QuestionCompletionCallbackWake wake;

	public QuestionCompletionCallbackRecoveryService(
		QuestionCompletionCallbackRepository repository,
		QuestionCompletionCallbackWake wake
	) {
		this.repository = repository;
		this.wake = wake;
	}

	public void recover() {
		for (long questionId : repository.findPendingQuestionIds(RECOVERY_BATCH_SIZE)) {
			wake.wake(questionId);
		}
	}
}
