package shinhan.fibri.ieum.main.auth.exception;

public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		this("Invalid email or password");
	}

	public InvalidCredentialsException(String message) {
		super(message);
	}
}
