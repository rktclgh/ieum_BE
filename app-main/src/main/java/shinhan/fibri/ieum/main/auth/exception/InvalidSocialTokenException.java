package shinhan.fibri.ieum.main.auth.exception;

public class InvalidSocialTokenException extends RuntimeException {

	public InvalidSocialTokenException() {
		super("Invalid social token");
	}
}
