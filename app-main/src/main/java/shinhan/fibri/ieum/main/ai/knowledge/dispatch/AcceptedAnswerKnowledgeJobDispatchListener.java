package shinhan.fibri.ieum.main.ai.knowledge.dispatch;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import shinhan.fibri.ieum.main.answer.event.AcceptedHumanAnswerEvent;

@Slf4j
public class AcceptedAnswerKnowledgeJobDispatchListener {

	private final AcceptedAnswerKnowledgeJobDispatchClient dispatchClient;
	private final Executor executor;

	public AcceptedAnswerKnowledgeJobDispatchListener(
		AcceptedAnswerKnowledgeJobDispatchClient dispatchClient,
		Executor executor
	) {
		this.dispatchClient = Objects.requireNonNull(dispatchClient, "dispatchClient must not be null");
		this.executor = Objects.requireNonNull(executor, "executor must not be null");
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onAcceptedHumanAnswer(AcceptedHumanAnswerEvent event) {
		try {
			executor.execute(() -> dispatchBestEffort(event.answerId()));
		}
		catch (RejectedExecutionException exception) {
			log.warn(
				"Accepted answer knowledge dispatch was shed: answerId={}, errorType={}",
				event.answerId(),
				exception.getClass().getSimpleName()
			);
		}
	}

	private void dispatchBestEffort(Long answerId) {
		try {
			dispatchClient.dispatch(answerId);
		}
		catch (RuntimeException exception) {
			log.warn(
				"Accepted answer knowledge dispatch failed: answerId={}, errorType={}",
				answerId,
				exception.getClass().getSimpleName()
			);
		}
	}
}
