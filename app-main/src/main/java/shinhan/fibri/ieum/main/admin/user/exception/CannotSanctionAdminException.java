package shinhan.fibri.ieum.main.admin.user.exception;

public class CannotSanctionAdminException extends RuntimeException {

	public CannotSanctionAdminException() {
		super("Admin users cannot be sanctioned");
	}
}
