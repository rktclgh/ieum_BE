package shinhan.fibri.ieum.ai.question.webgrounding;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record WebGroundedAnswer(
	String answer,
	List<WebGroundedCitation> citations,
	String provider,
	String model,
	String promptVersion,
	Instant generatedAt,
	Integer inputTokens,
	Integer outputTokens,
	String requestId,
	BigDecimal groundingScore
) {

	private static final int MAX_CITATIONS = 8;

	public WebGroundedAnswer {
		if (answer == null || answer.isBlank()) {
			throw new IllegalArgumentException("answer must not be blank");
		}
		Objects.requireNonNull(citations, "citations must not be null");
		citations = List.copyOf(citations);
		if (citations.isEmpty() || citations.size() > MAX_CITATIONS) {
			throw new IllegalArgumentException("citations must contain 1 to 8 items");
		}
		validateCitations(answer, citations);
		provider = required(provider, "provider", 40);
		model = required(model, "model", 120);
		promptVersion = required(promptVersion, "promptVersion", 80);
		generatedAt = Objects.requireNonNull(generatedAt, "generatedAt must not be null");
		inputTokens = nonNegative(inputTokens, "inputTokens");
		outputTokens = nonNegative(outputTokens, "outputTokens");
		requestId = optional(requestId);
		groundingScore = probability(groundingScore, "groundingScore");
	}

	private static void validateCitations(String answer, List<WebGroundedCitation> citations) {
		Set<CitationRange> ranges = new HashSet<>();
		for (WebGroundedCitation citation : citations) {
			Objects.requireNonNull(citation, "citations must not contain null");
			if (citation.endIndex() > answer.length()) {
				throw new IllegalArgumentException("citation range must be inside answer");
			}
			if (splitsSurrogatePair(answer, citation.startIndex())
				|| splitsSurrogatePair(answer, citation.endIndex())) {
				throw new IllegalArgumentException("citation range must not split a UTF-16 surrogate pair");
			}
			if (!answer.substring(citation.startIndex(), citation.endIndex()).equals(citation.excerpt())) {
				throw new IllegalArgumentException("citation excerpt must exactly match its answer range");
			}
			if (!ranges.add(new CitationRange(citation.startIndex(), citation.endIndex()))) {
				throw new IllegalArgumentException("citation answer ranges must be unique");
			}
		}
	}

	private static boolean splitsSurrogatePair(String value, int index) {
		return index > 0
			&& index < value.length()
			&& Character.isHighSurrogate(value.charAt(index - 1))
			&& Character.isLowSurrogate(value.charAt(index));
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

	private static BigDecimal probability(BigDecimal value, String field) {
		if (value == null
			|| value.compareTo(BigDecimal.ZERO) < 0
			|| value.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException(field + " must be between 0 and 1");
		}
		return value;
	}

	private record CitationRange(int startIndex, int endIndex) {
	}
}
