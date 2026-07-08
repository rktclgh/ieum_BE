package shinhan.fibri.ieum.main.friend.exception;

public class SelfFriendActionException extends RuntimeException {

	public SelfFriendActionException() {
		super("Cannot perform friendship action with self");
	}
}
