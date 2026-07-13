package shinhan.fibri.ieum.ai.question.generation;

public record LocalAnswerEvidence(
	int evidenceIndex,
	String title,
	String excerpt,
	String sourceType
) {

	public LocalAnswerEvidence {
		if (evidenceIndex < 0) {
			throw new IllegalArgumentException("evidenceIndex must be non-negative");
		}
		title = required(title, "title");
		excerpt = required(excerpt, "excerpt");
		sourceType = required(sourceType, "sourceType");
	}

	private static String required(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.trim();
	}
}
