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
import shinhan.fibri.ieum.main.ai.knowledge.dispatch.AcceptedAnswerKnowledgeJobDispatchClient;
import shinhan.fibri.ieum.main.ai.knowledge.dispatch.AcceptedAnswerKnowledgeJobDispatchListener;
import shinhan.fibri.ieum.main.ai.knowledge.dispatch.RestClientAcceptedAnswerKnowledgeJobDispatchClient;

@Configuration
@ConditionalOnProperty(
	prefix = "app.ai.accepted-answer-dispatch",
	name = "enabled",
	havingValue = "true"
)
public class AcceptedAnswerKnowledgeDispatchConfig {

	private static final int DISPATCH_QUEUE_CAPACITY = 32;

	@Bean
	AcceptedAnswerKnowledgeDispatchProperties acceptedAnswerKnowledgeDispatchProperties(
		@Value("${app.ai.accepted-answer-dispatch.base-url:}") String baseUrl,
		@Value("${app.ai.accepted-answer-dispatch.allowed-hosts:}") String allowedHosts,
		@Value("${app.ai.accepted-answer-dispatch.connect-timeout-seconds:2}") long connectTimeoutSeconds,
		@Value("${app.ai.accepted-answer-dispatch.read-timeout-seconds:5}") long readTimeoutSeconds
	) {
		return new AcceptedAnswerKnowledgeDispatchProperties(
			baseUrl,
			allowedHosts,
			Duration.ofSeconds(connectTimeoutSeconds),
			Duration.ofSeconds(readTimeoutSeconds)
		);
	}

	@Bean
	AcceptedAnswerKnowledgeJobDispatchClient acceptedAnswerKnowledgeJobDispatchClient(
		AcceptedAnswerKnowledgeDispatchProperties properties
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
		return new RestClientAcceptedAnswerKnowledgeJobDispatchClient(restClient);
	}

	@Bean("acceptedAnswerKnowledgeDispatchTaskExecutor")
	ThreadPoolTaskExecutor acceptedAnswerKnowledgeDispatchTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("ieum-accepted-answer-dispatch-");
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
	AcceptedAnswerKnowledgeJobDispatchListener acceptedAnswerKnowledgeJobDispatchListener(
		AcceptedAnswerKnowledgeJobDispatchClient dispatchClient,
		@Qualifier("acceptedAnswerKnowledgeDispatchTaskExecutor") Executor executor
	) {
		return new AcceptedAnswerKnowledgeJobDispatchListener(dispatchClient, executor);
	}
}
