package shinhan.fibri.ieum.ai.question.analysis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.question-answer.analyzer")
public record QuestionAnalyzerProperties(
	String model,
	String analysisVersion,
	int maxTokens,
	String bedrockRegion,
	Duration modelTimeout
) {

	private static final int MIN_MAX_TOKENS = 128;
	private static final int MAX_MAX_TOKENS = 2048;
	private static final int MAX_ANALYSIS_VERSION_LENGTH = 80;
	private static final String REQUIRED_BEDROCK_REGION = "ap-northeast-2";
	private static final Duration REQUIRED_MODEL_TIMEOUT = Duration.ofSeconds(30);

	public QuestionAnalyzerProperties {
		model = required(model, "model");
		analysisVersion = required(analysisVersion, "analysisVersion");
		if (analysisVersion.length() > MAX_ANALYSIS_VERSION_LENGTH) {
			throw new IllegalArgumentException("analysisVersion must contain at most 80 characters");
		}
		if (maxTokens < MIN_MAX_TOKENS || maxTokens > MAX_MAX_TOKENS) {
			throw new IllegalArgumentException("maxTokens must be between 128 and 2048");
		}
		bedrockRegion = required(bedrockRegion, "bedrockRegion");
		if (!REQUIRED_BEDROCK_REGION.equals(bedrockRegion)) {
			throw new IllegalArgumentException("bedrockRegion must be " + REQUIRED_BEDROCK_REGION);
		}
		if (!REQUIRED_MODEL_TIMEOUT.equals(modelTimeout)) {
			throw new IllegalArgumentException("modelTimeout must be 30 seconds");
		}
	}

	private static String required(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return value.trim();
	}
}
