package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TranslationConfigTest {

	@Test
	void propertiesUseStrictTimeoutsAndNeverRedirects() {
		TranslationProperties properties = new TranslationProperties("key", Duration.ofSeconds(2), Duration.ofSeconds(5));

		assertThat(properties.apiKey()).isEqualTo("key");
		assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
		assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
	}

	@Test
	void httpClientRejectsRedirects() {
		HttpClient client = TranslationConfig.translationHttpClient(Duration.ofSeconds(2));

		assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
		assertThat(client.connectTimeout()).contains(Duration.ofSeconds(2));
	}
}
