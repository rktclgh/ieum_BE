package shinhan.fibri.ieum.ai.question.webgrounding;

record GeminiWebGroundingRequest(
	String model,
	String systemInstruction,
	String userInstruction,
	int maxOutputTokens
) {

	private static final String REQUIRED_MODEL = "gemini-3.1-flash-lite";
	private static final int MIN_MAX_OUTPUT_TOKENS = 128;
	private static final int MAX_MAX_OUTPUT_TOKENS = 8192;

	GeminiWebGroundingRequest {
		if (!REQUIRED_MODEL.equals(model)) {
			throw new IllegalArgumentException("model must be " + REQUIRED_MODEL);
		}
		systemInstruction = required(systemInstruction, "systemInstruction");
		userInstruction = required(userInstruction, "userInstruction");
		if (maxOutputTokens < MIN_MAX_OUTPUT_TOKENS
			|| maxOutputTokens > MAX_MAX_OUTPUT_TOKENS) {
			throw new IllegalArgumentException("maxOutputTokens must be between 128 and 8192");
		}
	}

	private static String required(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.trim();
	}
}
