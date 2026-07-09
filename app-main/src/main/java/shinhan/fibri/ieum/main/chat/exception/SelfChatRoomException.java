package shinhan.fibri.ieum.main.chat.exception;

public class SelfChatRoomException extends RuntimeException {

	public SelfChatRoomException() {
		super("Cannot create chat room with self");
	}
}
