package shinhan.fibri.ieum.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import shinhan.fibri.ieum.main.ai.client.AiQuestionRecommendationClient;
import shinhan.fibri.ieum.main.ai.client.RestClientAiQuestionRecommendationClient;

@Configuration
@ConditionalOnProperty(prefix = "app.ai.question-recommendations", name = "enabled", havingValue = "true")
public class AiQuestionRecommendationClientConfig {

	@Bean
	QuestionRecommendationClientProperties questionRecommendationClientProperties(
		@Value("${app.ai.question-recommendations.base-url:}") String baseUrl,
		@Value("${app.ai.question-recommendations.allowed-hosts:}") String allowedHosts,
		@Value("${app.ai.question-recommendations.connect-timeout-seconds:2}") long connectTimeoutSeconds,
		@Value("${app.ai.question-recommendations.read-timeout-seconds:12}") long readTimeoutSeconds
	) {
		return new QuestionRecommendationClientProperties(
			baseUrl,
			allowedHosts,
			Duration.ofSeconds(connectTimeoutSeconds),
			Duration.ofSeconds(readTimeoutSeconds)
		);
	}

	@Bean
	AiQuestionRecommendationClient aiQuestionRecommendationClient(
		QuestionRecommendationClientProperties properties,
		ObjectMapper objectMapper
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
		return new RestClientAiQuestionRecommendationClient(restClient, objectMapper);
	}
}
