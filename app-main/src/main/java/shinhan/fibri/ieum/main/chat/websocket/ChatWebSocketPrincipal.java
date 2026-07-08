package shinhan.fibri.ieum.main.chat.websocket;

import java.security.Principal;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;

public record ChatWebSocketPrincipal(
	AuthenticatedUser authenticatedUser,
	String sessionId
) implements Principal {

	public static final String ATTRIBUTE_NAME = ChatWebSocketPrincipal.class.getName();

	@Override
	public String getName() {
		return String.valueOf(authenticatedUser.userId());
	}
}
