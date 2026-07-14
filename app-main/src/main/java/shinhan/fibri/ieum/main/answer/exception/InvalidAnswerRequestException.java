package shinhan.fibri.ieum.main.answer.exception;

public class InvalidAnswerRequestException extends RuntimeException {

	private final String code;
	private final String field;

	public InvalidAnswerRequestException(String code, String field, String message) {
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
