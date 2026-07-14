package shinhan.fibri.ieum.ai.question.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.net.http.HttpClient;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import shinhan.fibri.ieum.ai.question.service.QuestionCompletionCallbackWake;

class QuestionCompletionCallbackConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(QuestionCompletionCallbackConfiguration.class)
		.withBean(QuestionCompletionCallbackRepository.class, () -> mock(QuestionCompletionCallbackRepository.class));

	@Test
	void disabledQuestionAnswerFeatureCreatesNoCallbackDeliveryBeans() {
		contextRunner.run(context -> {
			assertThat(context).doesNotHaveBean(QuestionCompletionCallbackWake.class);
			assertThat(context).doesNotHaveBean(QuestionCompletionCallbackProperties.class);
		});
	}

	@Test
	void enabledQuestionAnswerFeatureFailsFastWhenCallbackConfigurationIsMissing() {
		contextRunner
			.withPropertyValues("app.ai.features.question-answer-enabled=true")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void enabledFeatureCreatesTheRealWakeClientAndNeverRedirectingBoundedExecutor() {
		contextRunner
			.withPropertyValues(
				"app.ai.features.question-answer-enabled=true",
				"app.ai.question-answer.callback.base-origin=http://app-main.internal:8080",
				"app.ai.question-answer.callback.allowed-origins=http://app-main.internal:8080",
				"app.ai.question-answer.callback.internal-token=shared-secret"
			)
			.run(context -> {
				assertThat(context).hasSingleBean(QuestionCompletionCallbackWake.class);
				assertThat(context).hasSingleBean(QuestionCompletionCallbackClient.class);
				assertThat(context).hasSingleBean(QuestionCompletionCallbackProperties.class);
				assertThat(context).doesNotHaveBean("questionCompletionCallbackRecoveryService");
				assertThat(context).doesNotHaveBean("questionCompletionCallbackRecoveryScheduler");
				HttpClient client = context.getBean("questionCompletionCallbackHttpClient", HttpClient.class);
				assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
				ThreadPoolTaskExecutor executor = context.getBean(
					"questionCompletionCallbackExecutor",
					ThreadPoolTaskExecutor.class
				);
				assertThat(executor.getCorePoolSize()).isEqualTo(1);
				assertThat(executor.getMaxPoolSize()).isEqualTo(1);
				assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(32);
				assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
					.isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
			});
	}
}
