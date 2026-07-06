package shinhan.fibri.ieum.main.user.exception;

public class InvalidUserFieldException extends RuntimeException {

	private final String field;

	public InvalidUserFieldException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String field() {
		return field;
	}
}
