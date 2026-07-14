package shinhan.fibri.ieum.main.friend.exception;

public class FriendshipNotFoundException extends RuntimeException {

	public FriendshipNotFoundException() {
		super("Friendship not found");
	}
}
