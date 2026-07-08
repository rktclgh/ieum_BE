package shinhan.fibri.ieum.main.question.exception;

public class InvalidQuestionRequestException extends RuntimeException {

	private final String code;
	private final String field;

	public InvalidQuestionRequestException(String code, String field, String message) {
		super(message);
		this.code = code;
		this.field = field;
	}

	public String code() {
		return code;
	}

	public String field() {
		return field;
	}
}
