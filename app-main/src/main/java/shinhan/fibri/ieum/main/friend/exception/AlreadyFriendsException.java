package shinhan.fibri.ieum.main.friend.exception;

public class AlreadyFriendsException extends RuntimeException {

	public AlreadyFriendsException() {
		super("Users are already friends");
	}
}
