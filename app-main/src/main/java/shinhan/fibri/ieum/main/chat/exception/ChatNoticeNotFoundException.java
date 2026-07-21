package shinhan.fibri.ieum.main.chat.exception;

public class ChatNoticeNotFoundException extends RuntimeException {

	public ChatNoticeNotFoundException() {
		super("Chat notice not found");
	}
}
