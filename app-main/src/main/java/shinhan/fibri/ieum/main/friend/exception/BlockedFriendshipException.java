package shinhan.fibri.ieum.main.friend.exception;

public class BlockedFriendshipException extends RuntimeException {

	public BlockedFriendshipException() {
		super("Friendship is blocked");
	}
}
