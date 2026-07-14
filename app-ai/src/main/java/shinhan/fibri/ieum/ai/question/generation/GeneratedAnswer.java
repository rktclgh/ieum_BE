package shinhan.fibri.ieum.ai.question.generation;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import shinhan.fibri.ieum.ai.question.citation.AnswerCitation;

public record GeneratedAnswer(
	String answer,
	List<AnswerCitation> citations,
	String provider,
	String model,
	String promptVersion,
	Instant startedAt,
	Integer inputTokenCount,
	Integer outputTokenCount,
	String providerRequestId,
	String fallbackReason
) {

	public GeneratedAnswer {
		if (answer == null || answer.isBlank()) {
			throw new IllegalArgumentException("answer must not be blank");
		}
		Objects.requireNonNull(citations, "citations must not be null");
		citations = List.copyOf(citations);
		if (citations.isEmpty() || citations.size() > 8) {
			throw new IllegalArgumentException("citations must contain 1 to 8 items");
		}
		Set<AnswerRange> ranges = new HashSet<>();
		for (AnswerCitation citation : citations) {
			Objects.requireNonNull(citation, "citation must not be null");
			if (citation.endIndex() > answer.length()) {
				throw new IllegalArgumentException("citation range must be inside answer");
			}
			if (!isUnicodeBoundary(answer, citation.startIndex())
				|| !isUnicodeBoundary(answer, citation.endIndex())) {
				throw new IllegalArgumentException("citation range must not split a UTF-16 surrogate pair");
			}
			if (!ranges.add(new AnswerRange(citation.startIndex(), citation.endIndex()))) {
				throw new IllegalArgumentException("citation answer ranges must be unique");
			}
		}
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

	private static boolean isUnicodeBoundary(String value, int index) {
		return index <= 0
			|| index >= value.length()
			|| !(Character.isHighSurrogate(value.charAt(index - 1))
				&& Character.isLowSurrogate(value.charAt(index)));
	}

	private record AnswerRange(int start, int end) {
	}
}
