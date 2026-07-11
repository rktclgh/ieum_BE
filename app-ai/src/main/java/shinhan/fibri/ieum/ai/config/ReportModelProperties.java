package shinhan.fibri.ieum.ai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.report")
public record ReportModelProperties(
	String geminiApiKey,
	String geminiModel,
	String novaModel,
	String bedrockRegion,
	Duration modelTimeout,
	String promptVersion
) {

	private static final String BEDROCK_NOVA_REGION = "ap-southeast-2";

	public ReportModelProperties {
		geminiApiKey = required(geminiApiKey, "geminiApiKey");
		geminiModel = required(geminiModel, "geminiModel");
		novaModel = required(novaModel, "novaModel");
		bedrockRegion = required(bedrockRegion, "bedrockRegion");
		promptVersion = required(promptVersion, "promptVersion");
		if (!BEDROCK_NOVA_REGION.equals(bedrockRegion)) {
			throw new IllegalArgumentException("bedrockRegion must be " + BEDROCK_NOVA_REGION);
		}
		if (modelTimeout == null || modelTimeout.isZero() || modelTimeout.isNegative()) {
			throw new IllegalArgumentException("modelTimeout must be positive");
		}
	}

	private static String required(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.trim();
	}
}
