package shinhan.fibri.ieum.main.auth.exception;

public class InvalidSocialSignupTokenException extends RuntimeException {

	public InvalidSocialSignupTokenException() {
		super("Invalid social signup token");
	}
}
