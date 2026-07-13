package shinhan.fibri.ieum.ai.question.service;

import java.time.Duration;
import shinhan.fibri.ieum.ai.question.repository.ClaimedQuestionTask;
import shinhan.fibri.ieum.ai.question.repository.QuestionTaskWorkRepository;

public class QuestionAnswerTaskProcessor {

	private final QuestionTaskWorkRepository repository;
	private final QuestionAnswerOrchestrator orchestrator;
	private final String workerId;
	private final Duration taskLease;
	private final int maxAttempts;

	public QuestionAnswerTaskProcessor(
		QuestionTaskWorkRepository repository,
		QuestionAnswerOrchestrator orchestrator,
		String workerId,
		Duration taskLease,
		int maxAttempts
	) {
		this.repository = repository;
		this.orchestrator = orchestrator;
		this.workerId = workerId;
		this.taskLease = taskLease;
		this.maxAttempts = maxAttempts;
	}

	public void process(long questionId) {
		repository.claimByQuestionId(questionId, workerId, taskLease, maxAttempts)
			.ifPresent(this::processClaim);
	}

	private void processClaim(ClaimedQuestionTask claim) {
		try {
			orchestrator.process(claim);
		}
		catch (RuntimeException exception) {
			if (claim.attempts() >= maxAttempts) {
				repository.markDead(claim.questionId(), claim.workerId(), claim.leaseToken());
				return;
			}
			repository.markRetry(
				claim.questionId(),
				claim.workerId(),
				claim.leaseToken(),
				retryDelay(claim.attempts())
			);
		}
	}

	private Duration retryDelay(int attempt) {
		return switch (attempt) {
			case 1 -> Duration.ofSeconds(10);
			case 2 -> Duration.ofSeconds(30);
			case 3 -> Duration.ofMinutes(2);
			case 4 -> Duration.ofMinutes(10);
			default -> throw new IllegalStateException("unsupported retry attempt: " + attempt);
		};
	}
}
