package shinhan.fibri.ieum.main.admin.user.exception;

public class SanctionAlreadyActiveException extends RuntimeException {

	public SanctionAlreadyActiveException() {
		super("User already has an active sanction");
	}
}
