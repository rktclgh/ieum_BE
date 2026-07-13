package shinhan.fibri.ieum.main.notification.internal;

public class AiQuestionAnswerCompletionConflictException extends RuntimeException {

	public AiQuestionAnswerCompletionConflictException() {
		super("AI answer job is not in a completable state");
	}
}
