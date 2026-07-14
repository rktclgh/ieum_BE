package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class AiServicePropertiesTest {

	@Test
	void acceptsPrivateHttpBaseUrl() {
		AiServiceProperties properties = properties("http://10.0.20.15:8080");

		assertThat(properties.baseUri().toString()).isEqualTo("http://10.0.20.15:8080");
	}

	@Test
	void rejectsBaseUrlWhoseHostIsNotExplicitlyAllowed() {
		assertThatThrownBy(() -> new AiServiceProperties(
			"http://public.example.test:8080",
			"10.0.20.15",
			Duration.ofSeconds(2),
			Duration.ofSeconds(90)
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("allowed-hosts");
	}

	private AiServiceProperties properties(String baseUrl) {
		return new AiServiceProperties(
			baseUrl,
			"10.0.20.15",
			Duration.ofSeconds(2),
			Duration.ofSeconds(90)
		);
	}

}
