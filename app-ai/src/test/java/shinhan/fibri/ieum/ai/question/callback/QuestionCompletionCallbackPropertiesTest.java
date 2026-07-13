package shinhan.fibri.ieum.ai.question.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class QuestionCompletionCallbackPropertiesTest {

	@Test
	void acceptsAnHttpBaseOriginThatIsExplicitlyAllowlisted() {
		QuestionCompletionCallbackProperties properties = QuestionCompletionCallbackProperties.create(
			"http://app-main.internal:8080",
			"http://app-main.internal:8080,https://main.example.com",
			"shared-secret",
			Duration.ofSeconds(2),
			Duration.ofSeconds(5)
		);

		assertThat(properties.baseOrigin().toString()).isEqualTo("http://app-main.internal:8080");
		assertThat(properties.allowedOrigins()).containsExactlyInAnyOrder(
			"http://app-main.internal:8080",
			"https://main.example.com"
		);
		assertThat(properties.internalToken()).isEqualTo("shared-secret");
		assertThat(properties.recoveryInterval()).isEqualTo(Duration.ofSeconds(60));
	}

	@Test
	void rejectsMissingSecretsUnsafeOriginsAndNonAllowlistedTargets() {
		assertThatThrownBy(() -> create("http://app-main.internal:8080", "http://app-main.internal:8080", " "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("token");
		assertThatThrownBy(() -> create("file:///tmp/app-main", "file:///tmp/app-main", "secret"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("HTTP");
		assertThatThrownBy(() -> create("http://app-main.internal:8080/api", "http://app-main.internal:8080", "secret"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("origin");
		assertThatThrownBy(() -> create("http://evil.internal:8080", "http://app-main.internal:8080", "secret"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("allowlist");
		assertThatThrownBy(() -> QuestionCompletionCallbackProperties.create(
			"http://app-main.internal:8080",
			"http://app-main.internal:8080",
			"secret",
			Duration.ZERO,
			Duration.ofSeconds(5)
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("timeout");
		assertThatThrownBy(() -> QuestionCompletionCallbackProperties.create(
			"http://app-main.internal:8080",
			"http://app-main.internal:8080",
			"secret",
			Duration.ofSeconds(2),
			Duration.ofSeconds(5),
			Duration.ofSeconds(9)
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("recovery interval");
	}

	private QuestionCompletionCallbackProperties create(String baseOrigin, String allowedOrigins, String token) {
		return QuestionCompletionCallbackProperties.create(
			baseOrigin,
			allowedOrigins,
			token,
			Duration.ofSeconds(2),
			Duration.ofSeconds(5)
		);
	}
}
