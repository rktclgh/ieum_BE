package shinhan.fibri.ieum.ai.question.finalization;

public record QuestionAnswerFinalizationResult(long questionId, Long answerId) {

	public QuestionAnswerFinalizationResult {
		if (questionId < 1) {
			throw new IllegalArgumentException("questionId must be positive");
		}
		if (answerId != null && answerId < 1) {
			throw new IllegalArgumentException("answerId must be positive when present");
		}
	}

	public boolean hasAnswer() {
		return answerId != null;
	}
}
