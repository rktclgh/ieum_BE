package shinhan.fibri.ieum.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GeminiApiKeyPropertiesTest {

	@Test
	void trimsTheSharedGeminiApiKey() {
		GeminiApiKeyProperties properties = new GeminiApiKeyProperties("  test-only-api-key  ");

		assertThat(properties.geminiApiKey()).isEqualTo("test-only-api-key");
		assertThat(GeminiApiKeyProperties.ENVIRONMENT_VARIABLE).isEqualTo("APP_AI_GEMINI_API_KEY");
	}

	@Test
	void rejectsABlankSharedGeminiApiKey() {
		assertThatThrownBy(() -> new GeminiApiKeyProperties("  "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("APP_AI_GEMINI_API_KEY");
	}
}
