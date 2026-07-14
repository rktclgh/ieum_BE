package shinhan.fibri.ieum.main.admin.user.exception;

public class UserNotSanctionedException extends RuntimeException {

	public UserNotSanctionedException() {
		super("User is not sanctioned");
	}
}
