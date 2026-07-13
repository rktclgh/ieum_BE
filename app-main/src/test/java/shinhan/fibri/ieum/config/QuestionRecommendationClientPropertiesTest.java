package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class QuestionRecommendationClientPropertiesTest {

	@Test
	void acceptsPrivateHttpBaseUrl() {
		QuestionRecommendationClientProperties properties = properties("http://10.0.20.15:8080");

		assertThat(properties.baseUri().toString()).isEqualTo("http://10.0.20.15:8080");
		assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(12));
	}

	@Test
	void rejectsBaseUrlWhoseHostIsNotExplicitlyAllowed() {
		assertThatThrownBy(() -> new QuestionRecommendationClientProperties(
			"http://public.example.test:8080",
			"10.0.20.15",
			Duration.ofSeconds(2),
			Duration.ofSeconds(12)
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.ai.question-recommendations.allowed-hosts");
	}

	@Test
	void rejectsNonOriginBaseUrl() {
		assertThatThrownBy(() -> new QuestionRecommendationClientProperties(
			"http://10.0.20.15:8080/api",
			"10.0.20.15",
			Duration.ofSeconds(2),
			Duration.ofSeconds(12)
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("origin URL");
	}

	@Test
	void rejectsNonPositiveTimeouts() {
		assertThatThrownBy(() -> new QuestionRecommendationClientProperties(
			"http://10.0.20.15:8080",
			"10.0.20.15",
			Duration.ZERO,
			Duration.ofSeconds(12)
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("connect-timeout-seconds");

		assertThatThrownBy(() -> new QuestionRecommendationClientProperties(
			"http://10.0.20.15:8080",
			"10.0.20.15",
			Duration.ofSeconds(2),
			Duration.ZERO
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("read-timeout-seconds");
	}

	private QuestionRecommendationClientProperties properties(String baseUrl) {
		return new QuestionRecommendationClientProperties(
			baseUrl,
			"10.0.20.15",
			Duration.ofSeconds(2),
			Duration.ofSeconds(12)
		);
	}
}
