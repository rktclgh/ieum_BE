package shinhan.fibri.ieum.main.chat.websocket;

import java.security.Principal;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

@Component
public class ChatWebSocketHandshakeHandler extends DefaultHandshakeHandler {

	@Override
	protected Principal determineUser(
		ServerHttpRequest request,
		WebSocketHandler wsHandler,
		Map<String, Object> attributes
	) {
		Object principal = attributes.get(ChatWebSocketPrincipal.ATTRIBUTE_NAME);
		if (principal instanceof ChatWebSocketPrincipal chatPrincipal) {
			return chatPrincipal;
		}
		return super.determineUser(request, wsHandler, attributes);
	}
}
