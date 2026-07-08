package shinhan.fibri.ieum.main.question.exception;

public class QuestionNotFoundException extends RuntimeException {

	public QuestionNotFoundException() {
		super("Question not found");
	}
}
