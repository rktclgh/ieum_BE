package shinhan.fibri.ieum.main.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.Principal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;

class ChatWebSocketHandshakeHandlerTest {

	private final TestHandshakeHandler handler = new TestHandshakeHandler();

	@Test
	void determineUserReturnsPrincipalFromHandshakeAttributes() {
		ChatWebSocketPrincipal principal = new ChatWebSocketPrincipal(
			new AuthenticatedUser(42L, "user@example.com", UserRole.user, UserStatus.active),
			"sid-1"
		);

		Principal result = handler.determineUser(Map.of(ChatWebSocketPrincipal.ATTRIBUTE_NAME, principal));

		assertThat(result).isEqualTo(principal);
	}

	private static class TestHandshakeHandler extends ChatWebSocketHandshakeHandler {

		private Principal determineUser(Map<String, Object> attributes) {
			return super.determineUser(null, null, attributes);
		}
	}
}
