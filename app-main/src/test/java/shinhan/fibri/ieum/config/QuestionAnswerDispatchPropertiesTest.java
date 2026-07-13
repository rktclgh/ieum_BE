package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class QuestionAnswerDispatchPropertiesTest {

	@Test
	void acceptsAnAllowlistedPrivateOriginAndDefaultTimeoutShape() {
		QuestionAnswerDispatchProperties properties = properties("http://10.0.20.15:8081");

		assertThat(properties.baseUri().toString()).isEqualTo("http://10.0.20.15:8081");
		assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
		assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
	}

	@Test
	void rejectsAnOriginWhoseHostIsNotExplicitlyAllowlisted() {
		assertThatThrownBy(() -> new QuestionAnswerDispatchProperties(
			"http://app-ai.internal:8081",
			"10.0.20.15",
			Duration.ofSeconds(2),
			Duration.ofSeconds(5)
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.ai.question-answer-dispatch.allowed-hosts");
	}

	@Test
	void rejectsANonOriginBaseUrl() {
		assertThatThrownBy(() -> new QuestionAnswerDispatchProperties(
			"http://10.0.20.15:8081/api",
			"10.0.20.15",
			Duration.ofSeconds(2),
			Duration.ofSeconds(5)
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("origin URL");
	}

	@Test
	void rejectsNonPositiveTimeouts() {
		assertThatThrownBy(() -> new QuestionAnswerDispatchProperties(
			"http://10.0.20.15:8081",
			"10.0.20.15",
			Duration.ZERO,
			Duration.ofSeconds(5)
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("connect-timeout-seconds");

		assertThatThrownBy(() -> new QuestionAnswerDispatchProperties(
			"http://10.0.20.15:8081",
			"10.0.20.15",
			Duration.ofSeconds(2),
			Duration.ZERO
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("read-timeout-seconds");
	}

	private QuestionAnswerDispatchProperties properties(String baseUrl) {
		return new QuestionAnswerDispatchProperties(
			baseUrl,
			"10.0.20.15",
			Duration.ofSeconds(2),
			Duration.ofSeconds(5)
		);
	}
}
