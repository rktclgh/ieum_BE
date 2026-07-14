package shinhan.fibri.ieum.ai.question.generation;

import java.util.Map;

record GeminiLocalAnswerRequest(
	String model,
	String systemInstruction,
	String userInstruction,
	String responseMimeType,
	Map<String, Object> responseJsonSchema,
	float temperature,
	int maxOutputTokens,
	boolean googleSearchGroundingEnabled
) {

	GeminiLocalAnswerRequest {
		model = required(model, "model");
		systemInstruction = required(systemInstruction, "systemInstruction");
		userInstruction = required(userInstruction, "userInstruction");
		if (!"application/json".equals(responseMimeType)) {
			throw new IllegalArgumentException("responseMimeType must be application/json");
		}
		if (responseJsonSchema == null || responseJsonSchema.isEmpty()) {
			throw new IllegalArgumentException("responseJsonSchema must not be empty");
		}
		responseJsonSchema = Map.copyOf(responseJsonSchema);
		if (!Float.isFinite(temperature) || Float.compare(temperature, 0.0f) != 0) {
			throw new IllegalArgumentException("temperature must be zero");
		}
		if (maxOutputTokens <= 0) {
			throw new IllegalArgumentException("maxOutputTokens must be positive");
		}
		if (googleSearchGroundingEnabled) {
			throw new IllegalArgumentException("Google Search grounding must remain disabled for local answers");
		}
	}

	private static String required(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.trim();
	}
}
