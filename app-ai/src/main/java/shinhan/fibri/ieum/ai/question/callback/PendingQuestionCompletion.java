package shinhan.fibri.ieum.ai.question.callback;

public record PendingQuestionCompletion(long questionId, long answerId) {

	public PendingQuestionCompletion {
		if (questionId <= 0 || answerId <= 0) {
			throw new IllegalArgumentException("Question and answer IDs must be positive");
		}
	}
}
