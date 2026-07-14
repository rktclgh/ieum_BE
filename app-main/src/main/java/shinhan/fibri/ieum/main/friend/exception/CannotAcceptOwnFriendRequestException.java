package shinhan.fibri.ieum.main.friend.exception;

public class CannotAcceptOwnFriendRequestException extends RuntimeException {

	public CannotAcceptOwnFriendRequestException() {
		super("Cannot accept own friend request");
	}
}
