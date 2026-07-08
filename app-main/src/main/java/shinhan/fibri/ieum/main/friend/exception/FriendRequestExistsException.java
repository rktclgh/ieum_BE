package shinhan.fibri.ieum.main.friend.exception;

public class FriendRequestExistsException extends RuntimeException {

	public FriendRequestExistsException() {
		super("Friend request already exists");
	}
}
