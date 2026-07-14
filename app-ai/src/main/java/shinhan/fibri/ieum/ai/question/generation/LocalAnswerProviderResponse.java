package shinhan.fibri.ieum.ai.question.generation;

import java.time.Instant;
import java.util.Objects;

record LocalAnswerProviderResponse(
	String rawOutput,
	Instant startedAt,
	Integer inputTokenCount,
	Integer outputTokenCount,
	String providerRequestId
) {

	LocalAnswerProviderResponse {
		startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
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
