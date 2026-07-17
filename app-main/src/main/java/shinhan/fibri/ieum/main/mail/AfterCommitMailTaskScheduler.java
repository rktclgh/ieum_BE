package shinhan.fibri.ieum.main.mail;

import java.util.Objects;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AfterCommitMailTaskScheduler {

	private static final Logger log = LoggerFactory.getLogger(AfterCommitMailTaskScheduler.class);

	private final Executor mailTaskExecutor;

	public AfterCommitMailTaskScheduler(@Qualifier("mailTaskExecutor") Executor mailTaskExecutor) {
		this.mailTaskExecutor = mailTaskExecutor;
	}

	public void executeAfterCommit(String event, Long resourceId, Runnable task) {
		Objects.requireNonNull(event, "event must not be null");
		Objects.requireNonNull(resourceId, "resourceId must not be null");
		Objects.requireNonNull(task, "task must not be null");
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			submit(event, resourceId, task);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				submit(event, resourceId, task);
			}
		});
	}

	private void submit(String event, Long resourceId, Runnable task) {
		try {
			mailTaskExecutor.execute(task);
		} catch (RuntimeException exception) {
			log.error(
				"event={}_enqueue_failed resourceId={} failureType={}",
				event,
				resourceId,
				exception.getClass().getSimpleName()
			);
		}
	}
}
