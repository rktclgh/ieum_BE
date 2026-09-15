package shinhan.fibri.ieum.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import shinhan.fibri.ieum.main.ai.question.dispatch.QuestionAnswerJobDispatchClient;
import shinhan.fibri.ieum.main.ai.question.dispatch.QuestionAnswerJobDispatchListener;
import shinhan.fibri.ieum.main.ai.question.dispatch.RestClientQuestionAnswerJobDispatchClient;

/**
 * app-ai로의 질문 답변 작업 디스패치 HTTP 경로(리스너 + REST client). {@code transport=http}
 * 롤백 전용이며, 정상 경로는 {@code app.ai.dispatch.transport=rabbitmq}(application.properties 주석
 * 참고). 기존 {@code app.ai.question-answer-dispatch.enabled} 조건은 그대로 유지한다 — 운영 env가
 * 이미 {@code true}로 켜져 있으므로 이 조건 하나만 바꾸면 배포가 깨진다. 두 조건은 AND다: 전송이
 * http이고 *이 기능 자체가* 켜져 있어야 이 설정이 활성화된다.
 */
@Configuration
@ConditionalOnProperty(
	prefix = "app.ai.question-answer-dispatch",
	name = "enabled",
	havingValue = "true"
)
@ConditionalOnExpression("'${app.ai.dispatch.transport:http}'.equals('http')")
public class QuestionAnswerDispatchConfig {

	private static final int DISPATCH_QUEUE_CAPACITY = 32;

	@Bean
	QuestionAnswerDispatchProperties questionAnswerDispatchProperties(
		@Value("${app.ai.question-answer-dispatch.base-url:}") String baseUrl,
		@Value("${app.ai.question-answer-dispatch.allowed-hosts:}") String allowedHosts,
		@Value("${app.ai.question-answer-dispatch.connect-timeout-seconds:2}") long connectTimeoutSeconds,
		@Value("${app.ai.question-answer-dispatch.read-timeout-seconds:5}") long readTimeoutSeconds
	) {
		return new QuestionAnswerDispatchProperties(
			baseUrl,
			allowedHosts,
			Duration.ofSeconds(connectTimeoutSeconds),
			Duration.ofSeconds(readTimeoutSeconds)
		);
	}

	@Bean
	QuestionAnswerJobDispatchClient questionAnswerJobDispatchClient(
		QuestionAnswerDispatchProperties properties
	) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());
		RestClient restClient = RestClient.builder()
			.baseUrl(properties.baseUri())
			.requestFactory(requestFactory)
			.build();
		return new RestClientQuestionAnswerJobDispatchClient(restClient);
	}

	@Bean("questionAnswerDispatchTaskExecutor")
	ThreadPoolTaskExecutor questionAnswerDispatchTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("ieum-question-answer-dispatch-");
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(DISPATCH_QUEUE_CAPACITY);
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(5);
		executor.initialize();
		return executor;
	}

	@Bean
	QuestionAnswerJobDispatchListener questionAnswerJobDispatchListener(
		QuestionAnswerJobDispatchClient dispatchClient,
		@Qualifier("questionAnswerDispatchTaskExecutor") Executor executor
	) {
		return new QuestionAnswerJobDispatchListener(dispatchClient, executor);
	}
}
