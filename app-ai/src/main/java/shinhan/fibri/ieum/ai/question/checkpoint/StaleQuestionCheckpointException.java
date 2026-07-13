package shinhan.fibri.ieum.ai.question.checkpoint;

public class StaleQuestionCheckpointException extends RuntimeException {

	public StaleQuestionCheckpointException(long questionId) {
		super("Question checkpoint fence is stale: " + questionId);
	}
}
