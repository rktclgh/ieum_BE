package shinhan.fibri.ieum.main.chat.exception;

public class NotFriendsException extends RuntimeException {

	public NotFriendsException() {
		super("Users are not friends");
	}
}
