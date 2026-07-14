package shinhan.fibri.ieum.main.user.exception;

public class UserNotFoundException extends RuntimeException {

	public UserNotFoundException() {
		super("User not found");
	}
}
