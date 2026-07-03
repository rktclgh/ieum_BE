package shinhan.fibri.ieum.main.auth.exception;

public class EmailTakenException extends RuntimeException {

	public EmailTakenException() {
		super("Email is already taken");
	}
}
