package shinhan.fibri.ieum.main.question.exception;

public class QuestionResolvedException extends RuntimeException {

	public QuestionResolvedException() {
		super("Resolved question cannot be edited");
	}
}
