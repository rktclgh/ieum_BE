package shinhan.fibri.ieum.ai.question.generation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.ai.question-answer.ungrounded")
public record UngroundedAnswerProperties(
	String model,
	String promptVersion,
	@DefaultValue("1024") int maxTokens,
	@DefaultValue("30s") Duration modelTimeout
) {

	private static final Duration REQUIRED_MODEL_TIMEOUT = Duration.ofSeconds(30);
	private static final int MIN_MAX_TOKENS = 128;
	private static final int MAX_MAX_TOKENS = 8192;

	public UngroundedAnswerProperties {
		model = required(model, "model", 120);
		promptVersion = required(promptVersion, "promptVersion", 80);
		if (maxTokens < MIN_MAX_TOKENS || maxTokens > MAX_MAX_TOKENS) {
			throw new IllegalArgumentException("maxTokens must be between 128 and 8192");
		}
		if (!REQUIRED_MODEL_TIMEOUT.equals(modelTimeout)) {
			throw new IllegalArgumentException("modelTimeout must be 30 seconds");
		}
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
