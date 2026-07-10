package shinhan.fibri.ieum.main.admin.user.exception;

public class InvalidSanctionRequestException extends RuntimeException {

	private final String field;

	public InvalidSanctionRequestException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String field() {
		return field;
	}
}
