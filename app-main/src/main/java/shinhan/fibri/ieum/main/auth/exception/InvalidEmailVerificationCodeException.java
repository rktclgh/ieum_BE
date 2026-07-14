package shinhan.fibri.ieum.main.auth.exception;

public class InvalidEmailVerificationCodeException extends RuntimeException {

	public InvalidEmailVerificationCodeException() {
		super("Invalid email verification code");
	}
}
