package shinhan.fibri.ieum.main.admin.user.exception;

public class AdminUserNotFoundException extends RuntimeException {

	public AdminUserNotFoundException() {
		super("User not found");
	}
}
