package shinhan.fibri.ieum.main.answer.exception;

public class AnswerNotFoundException extends RuntimeException {

	public AnswerNotFoundException() {
		super("Answer not found");
	}
}
