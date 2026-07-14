package shinhan.fibri.ieum.main.pin.exception;

public class InvalidPinRequestException extends RuntimeException {

	private final String code;
	private final String field;

	public InvalidPinRequestException(String code, String field, String message) {
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
