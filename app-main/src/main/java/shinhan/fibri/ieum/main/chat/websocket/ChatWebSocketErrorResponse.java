package shinhan.fibri.ieum.main.chat.websocket;

public record ChatWebSocketErrorResponse(
	String code,
	String message,
	Long roomId
) {
}
