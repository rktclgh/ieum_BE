package shinhan.fibri.ieum.ai.question.embedding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.ai.question-answer.embedding")
public record QuestionEmbeddingProperties(
	String geminiApiKey,
	@DefaultValue("10s") Duration modelTimeout,
	@DefaultValue("1") int totalAttempts
) {

	public static final String API_KEY_ENVIRONMENT_VARIABLE = "APP_AI_GEMINI_API_KEY";
	static final Duration REQUIRED_MODEL_TIMEOUT = Duration.ofSeconds(10);
	static final int REQUIRED_TOTAL_ATTEMPTS = 1;

	public QuestionEmbeddingProperties {
		geminiApiKey = required(geminiApiKey);
		if (!REQUIRED_MODEL_TIMEOUT.equals(modelTimeout)) {
			throw new IllegalArgumentException("Embedding model timeout must be exactly 10 seconds");
		}
		if (totalAttempts != REQUIRED_TOTAL_ATTEMPTS) {
			throw new IllegalArgumentException("Embedding transport attempts must be exactly 1");
		}
	}

	private static String required(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(API_KEY_ENVIRONMENT_VARIABLE + " must not be blank");
		}
		return value.trim();
	}
}
