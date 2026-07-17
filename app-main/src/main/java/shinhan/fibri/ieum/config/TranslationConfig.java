package shinhan.fibri.ieum.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import shinhan.fibri.ieum.main.translation.client.RestClientGoogleTranslationClient;
import shinhan.fibri.ieum.main.translation.service.TranslationClient;

@Configuration
public class TranslationConfig {

	@Bean
	TranslationProperties translationProperties(
		@Value("${app.translation.google.api-key:${GOOGLE_TRANSLATE_API_KEY:}}") String apiKey,
		@Value("${app.translation.google.connect-timeout-seconds:2}") long connectTimeoutSeconds,
		@Value("${app.translation.google.read-timeout-seconds:5}") long readTimeoutSeconds
	) {
		return new TranslationProperties(
			apiKey,
			Duration.ofSeconds(connectTimeoutSeconds),
			Duration.ofSeconds(readTimeoutSeconds)
		);
	}

	@Bean
	TranslationClient translationClient(TranslationProperties properties) {
		HttpClient httpClient = translationHttpClient(properties.connectTimeout());
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());
		RestClient restClient = RestClient.builder()
			.baseUrl("https://translation.googleapis.com")
			.requestFactory(requestFactory)
			.build();
		return new RestClientGoogleTranslationClient(restClient, properties.apiKey());
	}

	static HttpClient translationHttpClient(Duration connectTimeout) {
		return HttpClient.newBuilder()
			.connectTimeout(connectTimeout)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}
}
