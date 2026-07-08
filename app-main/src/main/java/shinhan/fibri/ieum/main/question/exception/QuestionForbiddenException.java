package shinhan.fibri.ieum.main.question.exception;

public class QuestionForbiddenException extends RuntimeException {

	public QuestionForbiddenException() {
		super("Question access forbidden");
	}
}
