package shinhan.fibri.ieum.main.auth.exception;

public class InvalidEmailVerificationTokenException extends RuntimeException {

	public InvalidEmailVerificationTokenException() {
		super("Invalid email verification token");
	}
}
