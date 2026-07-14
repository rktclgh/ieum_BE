package shinhan.fibri.ieum.main.auth.exception;

public class InvalidSignupFieldException extends RuntimeException {

	private final String field;

	public InvalidSignupFieldException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String field() {
		return field;
	}
}
