package shinhan.fibri.ieum.main.notification.internal;

public class AiQuestionAnswerTicketNotFoundException extends RuntimeException {

	public AiQuestionAnswerTicketNotFoundException() {
		super("AI answer job was not found");
	}
}
