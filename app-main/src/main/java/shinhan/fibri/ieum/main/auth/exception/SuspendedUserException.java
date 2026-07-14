package shinhan.fibri.ieum.main.auth.exception;

public class SuspendedUserException extends RuntimeException {

	public SuspendedUserException() {
		super("User is suspended");
	}
}
