package shinhan.fibri.ieum.main.admin.user.exception;

public class CannotDeleteSelfException extends RuntimeException {

	public CannotDeleteSelfException() {
		super("Administrators cannot hard delete their own account");
	}
}
