package shinhan.fibri.ieum.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public record GeminiApiKeyProperties(String geminiApiKey) {

	public static final String ENVIRONMENT_VARIABLE = "APP_AI_GEMINI_API_KEY";

	public GeminiApiKeyProperties {
		if (geminiApiKey == null || geminiApiKey.isBlank()) {
			throw new IllegalArgumentException(ENVIRONMENT_VARIABLE + " must not be blank");
		}
		geminiApiKey = geminiApiKey.trim();
	}
}
