package shinhan.fibri.ieum.main.chat.exception;

public class BlockedChatException extends RuntimeException {

	public BlockedChatException() {
		super("Chat is blocked");
	}
}
