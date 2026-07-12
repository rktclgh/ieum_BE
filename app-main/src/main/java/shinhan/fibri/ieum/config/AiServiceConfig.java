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
import shinhan.fibri.ieum.main.ai.client.AiServiceClient;
import shinhan.fibri.ieum.main.ai.client.RestClientAiServiceClient;

@Configuration
@ConditionalOnProperty(prefix = "app.ai.report", name = "enabled", havingValue = "true")
public class AiServiceConfig {

	@Bean
	AiServiceProperties aiServiceProperties(
		@Value("${app.ai.report.base-url:}") String baseUrl,
		@Value("${app.ai.report.allowed-hosts:}") String allowedHosts,
		@Value("${app.ai.report.connect-timeout-seconds:2}") long connectTimeoutSeconds,
		@Value("${app.ai.report.read-timeout-seconds:90}") long readTimeoutSeconds
	) {
		return new AiServiceProperties(
			baseUrl,
			allowedHosts,
			Duration.ofSeconds(connectTimeoutSeconds),
			Duration.ofSeconds(readTimeoutSeconds)
		);
	}

	@Bean
	AiServiceClient aiServiceClient(AiServiceProperties properties, ObjectMapper objectMapper) {
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
		return new RestClientAiServiceClient(restClient, objectMapper);
	}

}
