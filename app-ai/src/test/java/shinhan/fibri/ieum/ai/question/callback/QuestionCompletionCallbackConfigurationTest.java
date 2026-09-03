package shinhan.fibri.ieum.ai.question.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
				assertThat(context.getBean(QuestionCompletionCallbackClient.class))
					.isInstanceOf(HttpQuestionCompletionCallbackClient.class);
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

	@Test
	void rabbitmqTransportSelectsTheRabbitClientInsteadOfHttp() {
		contextRunner
			.withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
			.withBean(ObjectMapper.class, ObjectMapper::new)
			.withPropertyValues(
				"app.ai.features.question-answer-enabled=true",
				"app.ai.question-answer.callback.base-origin=http://app-main.internal:8080",
				"app.ai.question-answer.callback.allowed-origins=http://app-main.internal:8080",
				"app.ai.question-answer.callback.internal-token=shared-secret",
				"app.ai.question-answer.callback.transport=rabbitmq"
			)
			.run(context -> {
				assertThat(context).hasSingleBean(QuestionCompletionCallbackClient.class);
				assertThat(context.getBean(QuestionCompletionCallbackClient.class))
					.isInstanceOf(RabbitQuestionCompletionCallbackClient.class);
			});
	}

	/**
	 * CodeRabbit PR #257 finding 2. transport=rabbitmq 인 배포는 HTTP 콜백값
	 * (base-origin/allowed-origins/internal-token)을 채우지 않는다 — 채우지 않았다는 이유로 컨텍스트
	 * 기동이 실패해서는 안 된다. HTTP 콜백값이 아예 없어도 Rabbit 클라이언트만 정상적으로 뜬다.
	 */
	@Test
	void rabbitmqTransportStartsWithoutAnyHttpCallbackConfiguration() {
		contextRunner
			.withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
			.withBean(ObjectMapper.class, ObjectMapper::new)
			.withPropertyValues(
				"app.ai.features.question-answer-enabled=true",
				"app.ai.question-answer.callback.transport=rabbitmq"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(QuestionCompletionCallbackProperties.class);
				assertThat(context).doesNotHaveBean("questionCompletionCallbackHttpClient");
				assertThat(context).hasSingleBean(QuestionCompletionCallbackClient.class);
				assertThat(context.getBean(QuestionCompletionCallbackClient.class))
					.isInstanceOf(RabbitQuestionCompletionCallbackClient.class);
			});
	}
}
