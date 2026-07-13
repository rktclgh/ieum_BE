package shinhan.fibri.ieum.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import shinhan.fibri.ieum.main.ai.question.dispatch.QuestionAnswerJobDispatchClient;
import shinhan.fibri.ieum.main.ai.question.dispatch.QuestionAnswerJobDispatchListener;
import shinhan.fibri.ieum.main.ai.question.dispatch.RestClientQuestionAnswerJobDispatchClient;

@Configuration
@ConditionalOnProperty(
	prefix = "app.ai.question-answer-dispatch",
	name = "enabled",
	havingValue = "true"
)
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
