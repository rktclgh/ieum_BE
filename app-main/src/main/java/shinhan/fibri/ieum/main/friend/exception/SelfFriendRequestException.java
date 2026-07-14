package shinhan.fibri.ieum.main.friend.exception;

public class SelfFriendRequestException extends RuntimeException {

	public SelfFriendRequestException() {
		super("Cannot request friendship with self");
	}
}
