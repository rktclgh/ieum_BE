package shinhan.fibri.ieum.main.chat.websocket;

public interface ChatWebSocketErrorSender {

	void send(ChatWebSocketPrincipal principal, ChatWebSocketErrorResponse error);
}
