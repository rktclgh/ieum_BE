package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AcceptedAnswerKnowledgeDispatchPropertiesTest {

	@Test
	void acceptsAnAllowlistedOriginAndTimeouts() {
		AcceptedAnswerKnowledgeDispatchProperties properties = properties("http://10.0.20.15:8081");

		assertThat(properties.baseUri().toString()).isEqualTo("http://10.0.20.15:8081");
		assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
		assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
	}

	@Test
	void rejectsAnOriginWhoseHostIsNotAllowlisted() {
		assertThatThrownBy(() -> new AcceptedAnswerKnowledgeDispatchProperties(
			"http://app-ai.internal:8081",
			"10.0.20.15",
			Duration.ofSeconds(2),
			Duration.ofSeconds(5)
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.ai.accepted-answer-dispatch.allowed-hosts");
	}

	@Test
	void rejectsNonOriginBaseUrlsAndNonHttpSchemes() {
		assertThatThrownBy(() -> properties("http://10.0.20.15:8081/api"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("origin URL");

		assertThatThrownBy(() -> properties("ftp://10.0.20.15:8081"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("HTTP or HTTPS");
	}

	@Test
	void rejectsNonPositiveTimeouts() {
		assertThatThrownBy(() -> new AcceptedAnswerKnowledgeDispatchProperties(
			"http://10.0.20.15:8081",
			"10.0.20.15",
			Duration.ZERO,
			Duration.ofSeconds(5)
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("connect-timeout-seconds");

		assertThatThrownBy(() -> new AcceptedAnswerKnowledgeDispatchProperties(
			"http://10.0.20.15:8081",
			"10.0.20.15",
			Duration.ofSeconds(2),
			Duration.ZERO
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("read-timeout-seconds");
	}

	private AcceptedAnswerKnowledgeDispatchProperties properties(String baseUrl) {
		return new AcceptedAnswerKnowledgeDispatchProperties(
			baseUrl,
			"10.0.20.15",
			Duration.ofSeconds(2),
			Duration.ofSeconds(5)
		);
	}
}
