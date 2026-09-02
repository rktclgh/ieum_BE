package shinhan.fibri.ieum.ai.question.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * transport 선택: {@code app.ai.question-answer.callback.transport}. 브리프 "구현 단계" 3번.
 *
 * <p><b>기본값은 {@code http}다</b> — Task 8 이 이 값을 뒤집기 전까지 오늘의 HTTP 콜백 동작을
 * 그대로 유지한다({@code matchIfMissing = true}). {@code rabbitmq}로 바뀌면
 * {@link RabbitQuestionCompletionCallbackClient} 빈이 대신 선택된다. 두 조건이 상호 배타적이므로
 * {@link QuestionCompletionCallbackClient} 빈은 항상 정확히 하나만 존재한다.
 */
@Configuration
@ConditionalOnProperty(name = "app.ai.features.question-answer-enabled", havingValue = "true")
public class QuestionCompletionCallbackConfiguration {

	private static final int CALLBACK_QUEUE_CAPACITY = 32;
	private static final String TRANSPORT_PROPERTY = "app.ai.question-answer.callback.transport";

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
	@ConditionalOnProperty(name = TRANSPORT_PROPERTY, havingValue = "http", matchIfMissing = true)
	QuestionCompletionCallbackClient httpQuestionCompletionCallbackClient(
		@Qualifier("questionCompletionCallbackHttpClient") HttpClient httpClient,
		QuestionCompletionCallbackProperties properties
	) {
		return new HttpQuestionCompletionCallbackClient(httpClient, properties);
	}

	@Bean
	@ConditionalOnProperty(name = TRANSPORT_PROPERTY, havingValue = "rabbitmq")
	QuestionCompletionCallbackClient rabbitQuestionCompletionCallbackClient(
		RabbitTemplate rabbitTemplate,
		ObjectMapper objectMapper,
		@Value("${app.ai.question-answer.callback.confirm-timeout:5s}") String confirmTimeout
	) {
		return new RabbitQuestionCompletionCallbackClient(
			rabbitTemplate, objectMapper, parseDuration(confirmTimeout, "confirm timeout")
		);
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
