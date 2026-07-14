package shinhan.fibri.ieum.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import shinhan.fibri.ieum.ai.question.repository.QuestionTaskWorkRepository;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerOrchestrator;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerTaskLane;
import shinhan.fibri.ieum.ai.question.service.QuestionCompletionCallbackWake;

class QuestionAnswerDispatchConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(QuestionAnswerDispatchConfiguration.class)
		.withBean(QuestionTaskWorkRepository.class, () -> mock(QuestionTaskWorkRepository.class));

	@Test
	void createsTheSingleWorkerThirtyTwoQueueAbortPolicyExecutor() {
		ThreadPoolTaskExecutor executor = new QuestionAnswerDispatchConfiguration().questionAnswerTaskExecutor();

		try {
			assertThat(executor.getCorePoolSize()).isEqualTo(1);
			assertThat(executor.getMaxPoolSize()).isEqualTo(1);
			assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(32);
			assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
				.isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
		}
		finally {
			executor.shutdown();
		}
	}

	@Test
	void validatesLeaseAttemptsAndRetryAfterBounds() {
		QuestionAnswerDispatchProperties defaults = new QuestionAnswerDispatchProperties(
			false,
			Duration.ofMinutes(2),
			5,
			5
		);
		assertThat(defaults.enabled()).isFalse();
		assertThat(defaults.taskLease()).isEqualTo(Duration.ofMinutes(2));

		assertThatThrownBy(() -> new QuestionAnswerDispatchProperties(
			true, Duration.ofMinutes(2), 6, 5
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("max attempts");
		assertThatThrownBy(() -> new QuestionAnswerDispatchProperties(
			true, Duration.ofMinutes(2), 5, 0
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("retry-after");
	}

	@Test
	void dispatchPropertiesExposeNoDatabaseRecoveryControls() {
		assertThat(QuestionAnswerDispatchProperties.class.getRecordComponents())
			.extracting(component -> component.getName())
			.doesNotContain("recoveryInterval", "recoveryBatchSize");
	}

	@Test
	void enabledConfigurationUsesTheTestOrchestratorAndCreatesAnEnabledLane() {
		contextRunner
			.withBean(QuestionAnswerOrchestrator.class, () -> task -> { })
			.withBean(QuestionCompletionCallbackWake.class, () -> questionId -> { })
			.withPropertyValues("app.ai.features.question-answer-enabled=true")
			.run(context -> {
				assertThat(context).hasSingleBean(QuestionAnswerOrchestrator.class);
				assertThat(context).hasSingleBean(QuestionAnswerTaskLane.class);
				assertThat(context.getBean(QuestionAnswerTaskLane.class).isEnabled()).isTrue();
				assertThat(context).doesNotHaveBean("questionTaskRecoveryService");
				assertThat(context).doesNotHaveBean("questionTaskRecoveryScheduler");
			});
	}

	@Test
	void enabledConfigurationFailsFastWithoutACompletionCallbackWake() {
		contextRunner
			.withBean(QuestionAnswerOrchestrator.class, () -> task -> { })
			.withPropertyValues("app.ai.features.question-answer-enabled=true")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void enabledConfigurationFailsFastWithoutAnOrchestrator() {
		contextRunner
			.withPropertyValues("app.ai.features.question-answer-enabled=true")
			.run(context -> assertThat(context).hasFailed());
	}
}
