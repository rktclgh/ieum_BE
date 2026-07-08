package shinhan.fibri.ieum.main.answer.exception;

public class QuestionAlreadyResolvedException extends RuntimeException {

	public QuestionAlreadyResolvedException() {
		super("Question already resolved");
	}
}
