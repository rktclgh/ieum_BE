package shinhan.fibri.ieum.ai.question.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

public class QuestionTaskRecoveryScheduler {

	private final QuestionTaskRecoveryService recoveryService;

	public QuestionTaskRecoveryScheduler(QuestionTaskRecoveryService recoveryService) {
		this.recoveryService = recoveryService;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void recoverAtStartup() {
		recoveryService.recover();
	}

	@Scheduled(
		fixedDelayString = "${app.ai.question-answer.recovery-interval:60s}",
		initialDelayString = "${app.ai.question-answer.recovery-interval:60s}"
	)
	public void recoverPeriodically() {
		recoveryService.recover();
	}
}
