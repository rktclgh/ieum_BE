package shinhan.fibri.ieum.main.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.auth.session.ValidatedAuthSession;

class ChatWebSocketHandshakeInterceptorTest {

	private final SessionTokenValidator sessionTokenValidator = mock(SessionTokenValidator.class);
	private final ChatWebSocketHandshakeInterceptor interceptor = new ChatWebSocketHandshakeInterceptor(sessionTokenValidator);

	@Test
	void beforeHandshakeStoresPrincipalWhenAccessTokenCookieIsValid() throws Exception {
		MockHttpServletRequest servletRequest = new MockHttpServletRequest();
		servletRequest.setCookies(new Cookie("access_token", "access-token"));
		when(sessionTokenValidator.validateSession("access-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(
				new AuthenticatedUser(42L, "user@example.com", UserRole.user, UserStatus.active),
				"sid-1"
			)));
		Map<String, Object> attributes = new HashMap<>();

		boolean accepted = interceptor.beforeHandshake(
			new ServletServerHttpRequest(servletRequest),
			null,
			null,
			attributes
		);

		assertThat(accepted).isTrue();
		assertThat(attributes.get(ChatWebSocketPrincipal.ATTRIBUTE_NAME))
			.isEqualTo(new ChatWebSocketPrincipal(
				new AuthenticatedUser(42L, "user@example.com", UserRole.user, UserStatus.active),
				"sid-1"
			));
	}

	@Test
	void beforeHandshakeRejectsWhenAccessTokenCookieIsMissing() throws Exception {
		Map<String, Object> attributes = new HashMap<>();

		boolean accepted = interceptor.beforeHandshake(
			new ServletServerHttpRequest(new MockHttpServletRequest()),
			null,
			null,
			attributes
		);

		assertThat(accepted).isFalse();
		assertThat(attributes).doesNotContainKey(ChatWebSocketPrincipal.ATTRIBUTE_NAME);
	}

	@Test
	void beforeHandshakeRejectsWhenAccessTokenCookieIsInvalid() throws Exception {
		MockHttpServletRequest servletRequest = new MockHttpServletRequest();
		servletRequest.setCookies(new Cookie("access_token", "bad-token"));
		when(sessionTokenValidator.validateSession("bad-token")).thenReturn(Optional.empty());

		boolean accepted = interceptor.beforeHandshake(
			new ServletServerHttpRequest(servletRequest),
			null,
			null,
			new HashMap<>()
		);

		assertThat(accepted).isFalse();
	}
}
