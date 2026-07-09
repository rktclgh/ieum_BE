package shinhan.fibri.ieum.main.chat.exception;

public class NotRoomMemberException extends RuntimeException {

	public NotRoomMemberException() {
		super("User is not a room member");
	}
}
