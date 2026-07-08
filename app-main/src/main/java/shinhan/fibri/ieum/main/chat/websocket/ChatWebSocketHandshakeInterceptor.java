package shinhan.fibri.ieum.main.chat.websocket;

import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;

@Component
@RequiredArgsConstructor
public class ChatWebSocketHandshakeInterceptor implements HandshakeInterceptor {

	private final SessionTokenValidator sessionTokenValidator;

	@Override
	public boolean beforeHandshake(
		ServerHttpRequest request,
		ServerHttpResponse response,
		WebSocketHandler wsHandler,
		Map<String, Object> attributes
	) {
		return findAccessToken(request)
			.flatMap(sessionTokenValidator::validateSession)
			.map(session -> {
				attributes.put(ChatWebSocketPrincipal.ATTRIBUTE_NAME, new ChatWebSocketPrincipal(
					session.principal(),
					session.sessionId()
				));
				return true;
			})
			.orElse(false);
	}

	@Override
	public void afterHandshake(
		ServerHttpRequest request,
		ServerHttpResponse response,
		WebSocketHandler wsHandler,
		Exception exception
	) {
	}

	private Optional<String> findAccessToken(ServerHttpRequest request) {
		if (request instanceof ServletServerHttpRequest servletRequest) {
			Cookie[] cookies = servletRequest.getServletRequest().getCookies();
			if (cookies == null) {
				return Optional.empty();
			}
			return Arrays.stream(cookies)
				.filter(cookie -> "access_token".equals(cookie.getName()))
				.map(Cookie::getValue)
				.findFirst();
		}
		return Optional.empty();
	}
}
