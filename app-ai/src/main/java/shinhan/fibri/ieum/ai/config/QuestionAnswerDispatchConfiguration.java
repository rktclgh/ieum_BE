package shinhan.fibri.ieum.ai.config;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import shinhan.fibri.ieum.ai.question.repository.QuestionTaskWorkRepository;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerJobDispatchService;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerOrchestrator;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerTaskLane;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerTaskProcessor;
import shinhan.fibri.ieum.ai.question.service.QuestionCompletionCallbackWake;
import shinhan.fibri.ieum.ai.question.service.QuestionTaskRecoveryScheduler;
import shinhan.fibri.ieum.ai.question.service.QuestionTaskRecoveryService;

@Configuration
public class QuestionAnswerDispatchConfiguration {

	private static final int QUESTION_QUEUE_CAPACITY = 32;

	@Bean
	QuestionAnswerDispatchProperties questionAnswerDispatchProperties(
		@Value("${app.ai.features.question-answer-enabled:false}") boolean enabled,
		@Value("${app.ai.question-answer.task-lease:2m}") String taskLease,
		@Value("${app.ai.question-answer.max-attempts:5}") int maxAttempts,
		@Value("${app.ai.question-answer.recovery-interval:60s}") String recoveryInterval,
		@Value("${app.ai.question-answer.recovery-batch-size:32}") int recoveryBatchSize,
		@Value("${app.ai.question-answer.dispatch-retry-after-seconds:5}") int retryAfterSeconds
	) {
		return new QuestionAnswerDispatchProperties(
			enabled,
			DurationStyle.detectAndParse(taskLease),
			maxAttempts,
			DurationStyle.detectAndParse(recoveryInterval),
			recoveryBatchSize,
			retryAfterSeconds
		);
	}

	@Bean("questionAnswerTaskExecutor")
	public ThreadPoolTaskExecutor questionAnswerTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("ieum-question-answer-");
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(QUESTION_QUEUE_CAPACITY);
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(10);
		executor.initialize();
		return executor;
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(
		name = "app.ai.features.question-answer-enabled",
		havingValue = "false",
		matchIfMissing = true
	)
	QuestionCompletionCallbackWake questionCompletionCallbackWake() {
		return questionId -> { };
	}

	@Bean
	QuestionAnswerTaskProcessor questionAnswerTaskProcessor(
		QuestionTaskWorkRepository repository,
		ObjectProvider<QuestionAnswerOrchestrator> orchestratorProvider,
		QuestionAnswerDispatchProperties properties
	) {
		QuestionAnswerOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
		if (properties.enabled() && orchestrator == null) {
			throw new IllegalStateException(
				"Question answer feature requires a QuestionAnswerOrchestrator implementation"
			);
		}
		if (orchestrator == null) {
			orchestrator = task -> { };
		}
		return new QuestionAnswerTaskProcessor(
			repository,
			orchestrator,
			"question-worker-" + UUID.randomUUID(),
			properties.taskLease(),
			properties.maxAttempts()
		);
	}

	@Bean
	QuestionAnswerTaskLane questionAnswerTaskLane(
		QuestionAnswerDispatchProperties properties,
		@Qualifier("questionAnswerTaskExecutor") ThreadPoolTaskExecutor executor,
		QuestionAnswerTaskProcessor processor
	) {
		return new QuestionAnswerTaskLane(properties.enabled(), executor, processor::process);
	}

	@Bean
	QuestionAnswerJobDispatchService questionAnswerJobDispatchService(
		QuestionTaskWorkRepository repository,
		QuestionAnswerTaskLane lane,
		QuestionCompletionCallbackWake callbackWake
	) {
		return new QuestionAnswerJobDispatchService(repository, lane, callbackWake);
	}

	@Bean
	QuestionTaskRecoveryService questionTaskRecoveryService(
		QuestionTaskWorkRepository repository,
		QuestionAnswerTaskLane lane,
		QuestionAnswerDispatchProperties properties
	) {
		return new QuestionTaskRecoveryService(
			repository,
			lane,
			properties.maxAttempts(),
			properties.recoveryBatchSize()
		);
	}

	@Bean
	QuestionTaskRecoveryScheduler questionTaskRecoveryScheduler(QuestionTaskRecoveryService recoveryService) {
		return new QuestionTaskRecoveryScheduler(recoveryService);
	}
}
