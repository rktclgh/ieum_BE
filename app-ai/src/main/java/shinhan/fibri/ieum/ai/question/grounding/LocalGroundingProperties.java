package shinhan.fibri.ieum.ai.question.grounding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.question-answer.grounding")
public record LocalGroundingProperties(
	String validationPromptVersion,
	String repairPromptVersion,
	int validationMaxTokens,
	int repairMaxTokens,
	Duration modelTimeout
) {

	private static final String REQUIRED_VALIDATION_PROMPT_VERSION = "question-grounding-validation-v1";
	private static final String REQUIRED_REPAIR_PROMPT_VERSION = "question-grounding-repair-v1";
	private static final int REQUIRED_VALIDATION_MAX_TOKENS = 512;
	private static final int REQUIRED_REPAIR_MAX_TOKENS = 1024;
	private static final Duration REQUIRED_MODEL_TIMEOUT = Duration.ofSeconds(30);

	public LocalGroundingProperties {
		validationPromptVersion = exact(
			validationPromptVersion,
			REQUIRED_VALIDATION_PROMPT_VERSION,
			"validationPromptVersion"
		);
		repairPromptVersion = exact(
			repairPromptVersion,
			REQUIRED_REPAIR_PROMPT_VERSION,
			"repairPromptVersion"
		);
		if (validationMaxTokens != REQUIRED_VALIDATION_MAX_TOKENS) {
			throw new IllegalArgumentException("validationMaxTokens must be 512");
		}
		if (repairMaxTokens != REQUIRED_REPAIR_MAX_TOKENS) {
			throw new IllegalArgumentException("repairMaxTokens must be 1024");
		}
		if (!REQUIRED_MODEL_TIMEOUT.equals(modelTimeout)) {
			throw new IllegalArgumentException("modelTimeout must be 30 seconds");
		}
	}

	private static String exact(String value, String expected, String field) {
		if (value == null || !expected.equals(value.trim())) {
			throw new IllegalArgumentException(field + " must be " + expected);
		}
		return expected;
	}
}
