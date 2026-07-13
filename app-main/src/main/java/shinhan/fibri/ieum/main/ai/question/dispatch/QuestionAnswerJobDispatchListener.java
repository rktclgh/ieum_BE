package shinhan.fibri.ieum.main.ai.question.dispatch;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import shinhan.fibri.ieum.main.notification.presence.QuestionCreatedEvent;

@Slf4j
public class QuestionAnswerJobDispatchListener {

	private final QuestionAnswerJobDispatchClient dispatchClient;
	private final Executor executor;

	public QuestionAnswerJobDispatchListener(
		QuestionAnswerJobDispatchClient dispatchClient,
		Executor executor
	) {
		this.dispatchClient = Objects.requireNonNull(dispatchClient, "dispatchClient must not be null");
		this.executor = Objects.requireNonNull(executor, "executor must not be null");
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onQuestionCreated(QuestionCreatedEvent event) {
		try {
			executor.execute(() -> dispatchBestEffort(event.questionId()));
		}
		catch (RejectedExecutionException exception) {
			log.warn("Question answer dispatch wake was shed because the executor is saturated: questionId={}",
				event.questionId());
		}
	}

	private void dispatchBestEffort(Long questionId) {
		try {
			dispatchClient.dispatch(questionId);
		}
		catch (RuntimeException exception) {
			log.warn("Question answer dispatch wake failed and will be recovered by app-ai: questionId={}",
				questionId, exception);
		}
	}
}
