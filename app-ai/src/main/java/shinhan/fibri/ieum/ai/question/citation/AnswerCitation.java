package shinhan.fibri.ieum.ai.question.citation;

public record AnswerCitation(int evidenceIndex, int startIndex, int endIndex) {

	public AnswerCitation {
		if (evidenceIndex < 0) {
			throw new IllegalArgumentException("evidenceIndex must be non-negative");
		}
		if (startIndex < 0 || endIndex <= startIndex) {
			throw new IllegalArgumentException("citation indices must define a nonempty range");
		}
	}
}
