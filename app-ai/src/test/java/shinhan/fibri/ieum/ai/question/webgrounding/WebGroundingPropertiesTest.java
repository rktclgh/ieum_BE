package shinhan.fibri.ieum.ai.question.webgrounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class WebGroundingPropertiesTest {

	private static final String REQUIRED_MODEL = "gemini-3.1-flash-lite";

	@Test
	void bindsTrimmedValuesWithDocumentedDefaults() {
		MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
			"app.ai.question-answer.web-grounding.model", "  " + REQUIRED_MODEL + "  ",
			"app.ai.question-answer.web-grounding.gemini-api-key", "  test-only-api-key  ",
			"app.ai.question-answer.web-grounding.prompt-version", "  question-web-grounding-v1  "
		));

		WebGroundingProperties properties = new Binder(source)
			.bind(
				"app.ai.question-answer.web-grounding",
				Bindable.of(WebGroundingProperties.class)
			)
			.orElseThrow(() -> new AssertionError("web grounding properties were not bound"));

		assertThat(properties.model()).isEqualTo(REQUIRED_MODEL);
		assertThat(properties.geminiApiKey()).isEqualTo("test-only-api-key");
		assertThat(properties.promptVersion()).isEqualTo("question-web-grounding-v1");
		assertThat(properties.maxTokens()).isEqualTo(1024);
		assertThat(properties.modelTimeout()).isEqualTo(Duration.ofSeconds(45));
		assertThat(WebGroundingProperties.API_KEY_ENVIRONMENT_VARIABLE)
			.isEqualTo("APP_AI_QUESTION_WEB_GROUNDING_GEMINI_API_KEY");

		ConfigurationProperties annotation = WebGroundingProperties.class
			.getAnnotation(ConfigurationProperties.class);
		assertThat(annotation).isNotNull();
		assertThat(annotation.prefix()).isEqualTo("app.ai.question-answer.web-grounding");
	}

	@Test
	void acceptsInclusiveMaxTokenBounds() {
		assertThat(properties(128, Duration.ofSeconds(45)).maxTokens()).isEqualTo(128);
		assertThat(properties(8192, Duration.ofSeconds(45)).maxTokens()).isEqualTo(8192);
	}

	@Test
	void acceptsAConfiguredGroundingCapableGeminiModel() {
		WebGroundingProperties properties = new WebGroundingProperties(
			"  gemini-2.5-flash-lite  ",
			"api-key",
			"prompt-v1",
			1024,
			Duration.ofSeconds(45)
		);

		assertThat(properties.model()).isEqualTo("gemini-2.5-flash-lite");
	}

	@Test
	void rejectsBlankOrMissingModel() {
		assertThatThrownBy(() -> new WebGroundingProperties(
			"  ",
			"api-key",
			"prompt-v1",
			1024,
			Duration.ofSeconds(45)
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("model");
		assertThatThrownBy(() -> new WebGroundingProperties(
			null,
			"api-key",
			"prompt-v1",
			1024,
			Duration.ofSeconds(45)
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("model");
	}

	@Test
	void rejectsBlankSecretsAndInvalidPromptVersions() {
		assertThatThrownBy(() -> new WebGroundingProperties(
			REQUIRED_MODEL,
			"  ",
			"prompt-v1",
			1024,
			Duration.ofSeconds(45)
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("geminiApiKey");
		assertThatThrownBy(() -> new WebGroundingProperties(
			REQUIRED_MODEL,
			"api-key",
			"  ",
			1024,
			Duration.ofSeconds(45)
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("promptVersion");
		assertThatThrownBy(() -> new WebGroundingProperties(
			REQUIRED_MODEL,
			"api-key",
			"v".repeat(81),
			1024,
			Duration.ofSeconds(45)
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("promptVersion");
	}

	@Test
	void rejectsOutOfRangeTokensAndAnyTimeoutOtherThanFortyFiveSeconds() {
		assertThatThrownBy(() -> properties(127, Duration.ofSeconds(45)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxTokens");
		assertThatThrownBy(() -> properties(8193, Duration.ofSeconds(45)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxTokens");
		assertThatThrownBy(() -> properties(1024, Duration.ofSeconds(44)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("modelTimeout");
		assertThatThrownBy(() -> properties(1024, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("modelTimeout");
	}

	private WebGroundingProperties properties(int maxTokens, Duration timeout) {
		return new WebGroundingProperties(
			REQUIRED_MODEL,
			"api-key",
			"prompt-v1",
			maxTokens,
			timeout
		);
	}
}
