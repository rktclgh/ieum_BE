package shinhan.fibri.ieum.ai.question.webgrounding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.ai.question-answer.web-grounding")
public record WebGroundingProperties(
	String model,
	String geminiApiKey,
	String promptVersion,
	@DefaultValue("1024") int maxTokens,
	@DefaultValue("45s") Duration modelTimeout
) {

	public static final String API_KEY_ENVIRONMENT_VARIABLE =
		"APP_AI_QUESTION_WEB_GROUNDING_GEMINI_API_KEY";

	private static final String REQUIRED_MODEL = "gemini-3.1-flash-lite";
	private static final Duration REQUIRED_MODEL_TIMEOUT = Duration.ofSeconds(45);
	private static final int MIN_MAX_TOKENS = 128;
	private static final int MAX_MAX_TOKENS = 8192;

	public WebGroundingProperties {
		model = requiredModel(model);
		geminiApiKey = required(geminiApiKey, "geminiApiKey", Integer.MAX_VALUE);
		promptVersion = required(promptVersion, "promptVersion", 80);
		if (maxTokens < MIN_MAX_TOKENS || maxTokens > MAX_MAX_TOKENS) {
			throw new IllegalArgumentException("maxTokens must be between 128 and 8192");
		}
		if (!REQUIRED_MODEL_TIMEOUT.equals(modelTimeout)) {
			throw new IllegalArgumentException("modelTimeout must be 45 seconds");
		}
	}

	private static String requiredModel(String value) {
		if (value == null || !REQUIRED_MODEL.equals(value.trim())) {
			throw new IllegalArgumentException("model must be " + REQUIRED_MODEL);
		}
		return REQUIRED_MODEL;
	}

	private static String required(String value, String field, int maxLength) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		String normalized = value.trim();
		if (normalized.length() > maxLength) {
			throw new IllegalArgumentException(field + " is too long");
		}
		return normalized;
	}
}
