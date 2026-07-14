package shinhan.fibri.ieum.ai.question.grounding;

import java.time.Instant;
import java.util.Objects;

public record GroundingValidationResult(
	GroundingValidation validation,
	String provider,
	String model,
	String promptVersion,
	Instant startedAt,
	Integer inputTokenCount,
	Integer outputTokenCount,
	String providerRequestId,
	String fallbackReason
) {

	public GroundingValidationResult {
		validation = Objects.requireNonNull(validation, "validation must not be null");
		provider = required(provider, "provider", 40);
		model = required(model, "model", 120);
		promptVersion = required(promptVersion, "promptVersion", 80);
		startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
		inputTokenCount = nonNegative(inputTokenCount, "inputTokenCount");
		outputTokenCount = nonNegative(outputTokenCount, "outputTokenCount");
		providerRequestId = optional(providerRequestId);
		fallbackReason = optional(fallbackReason);
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

	private static Integer nonNegative(Integer value, String field) {
		if (value != null && value < 0) {
			throw new IllegalArgumentException(field + " must be non-negative");
		}
		return value;
	}

	private static String optional(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
