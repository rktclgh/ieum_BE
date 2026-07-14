package shinhan.fibri.ieum.ai.question.finalization;

public class StaleQuestionTaskFinalizationException extends RuntimeException {

	public StaleQuestionTaskFinalizationException(long questionId) {
		super("Question task finalization fence is stale: " + questionId);
	}
}
