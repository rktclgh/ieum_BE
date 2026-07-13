package shinhan.fibri.ieum.ai.question.generation;

record GeminiLocalAnswerClientResponse(
	String rawOutput,
	Integer inputTokenCount,
	Integer outputTokenCount,
	String providerRequestId
) {

	GeminiLocalAnswerClientResponse {
		inputTokenCount = nonNegative(inputTokenCount, "inputTokenCount");
		outputTokenCount = nonNegative(outputTokenCount, "outputTokenCount");
		providerRequestId = providerRequestId == null || providerRequestId.isBlank()
			? null
			: providerRequestId.trim();
	}

	private static Integer nonNegative(Integer value, String field) {
		if (value != null && value < 0) {
			throw new IllegalArgumentException(field + " must be non-negative");
		}
		return value;
	}
}
