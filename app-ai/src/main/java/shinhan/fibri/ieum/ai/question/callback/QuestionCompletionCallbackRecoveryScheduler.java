package shinhan.fibri.ieum.ai.question.callback;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

public class QuestionCompletionCallbackRecoveryScheduler {

	private final QuestionCompletionCallbackRecoveryService recoveryService;

	public QuestionCompletionCallbackRecoveryScheduler(
		QuestionCompletionCallbackRecoveryService recoveryService
	) {
		this.recoveryService = recoveryService;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void recoverAtStartup() {
		recoveryService.recover();
	}

	@Scheduled(
		fixedDelayString = "#{@questionCompletionCallbackProperties.recoveryInterval().toMillis()}",
		initialDelayString = "#{@questionCompletionCallbackProperties.recoveryInterval().toMillis()}"
	)
	public void recoverPeriodically() {
		recoveryService.recover();
	}
}
