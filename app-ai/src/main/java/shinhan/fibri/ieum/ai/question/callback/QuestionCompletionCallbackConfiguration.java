package shinhan.fibri.ieum.ai.question.callback;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@ConditionalOnProperty(name = "app.ai.features.question-answer-enabled", havingValue = "true")
public class QuestionCompletionCallbackConfiguration {

	private static final int CALLBACK_QUEUE_CAPACITY = 32;

	@Bean
	QuestionCompletionCallbackProperties questionCompletionCallbackProperties(
		@Value("${app.ai.question-answer.callback.base-origin:}") String baseOrigin,
		@Value("${app.ai.question-answer.callback.allowed-origins:}") String allowedOrigins,
		@Value("${app.ai.question-answer.callback.internal-token:}") String internalToken,
		@Value("${app.ai.question-answer.callback.connect-timeout:2s}") String connectTimeout,
		@Value("${app.ai.question-answer.callback.read-timeout:5s}") String readTimeout
	) {
		return QuestionCompletionCallbackProperties.create(
			baseOrigin,
			allowedOrigins,
			internalToken,
			parseDuration(connectTimeout, "connect timeout"),
			parseDuration(readTimeout, "read timeout")
		);
	}

	@Bean("questionCompletionCallbackHttpClient")
	HttpClient questionCompletionCallbackHttpClient(QuestionCompletionCallbackProperties properties) {
		return HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	@Bean("questionCompletionCallbackExecutor")
	public static ThreadPoolTaskExecutor callbackExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("ieum-question-callback-");
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(CALLBACK_QUEUE_CAPACITY);
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(10);
		executor.initialize();
		return executor;
	}

	@Bean
	QuestionCompletionCallbackClient questionCompletionCallbackClient(
		@Qualifier("questionCompletionCallbackHttpClient") HttpClient httpClient,
		QuestionCompletionCallbackProperties properties
	) {
		return new HttpQuestionCompletionCallbackClient(httpClient, properties);
	}

	@Bean
	QuestionCompletionCallbackDeliveryService questionCompletionCallbackDeliveryService(
		QuestionCompletionCallbackRepository repository,
		QuestionCompletionCallbackClient client
	) {
		return new QuestionCompletionCallbackDeliveryService(repository, client);
	}

	@Bean
	QuestionCompletionCallbackLane questionCompletionCallbackWake(
		@Qualifier("questionCompletionCallbackExecutor") ThreadPoolTaskExecutor executor,
		QuestionCompletionCallbackDeliveryService deliveryService
	) {
		return new QuestionCompletionCallbackLane(executor, deliveryService::deliver);
	}
	private static Duration parseDuration(String value, String field) {
		try {
			return DurationStyle.detectAndParse(value);
		}
		catch (RuntimeException exception) {
			throw new IllegalArgumentException("Invalid callback " + field, exception);
		}
	}
}
