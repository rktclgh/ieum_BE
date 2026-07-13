package shinhan.fibri.ieum.main.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;

class JwtAuthenticationFilterTest {

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void doFilterSetsAuthenticationWhenAccessCookieIsValid() throws Exception {
		SessionTokenValidator validator = mock(SessionTokenValidator.class);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator);
		AuthenticatedUser principal = new AuthenticatedUser(
			42L,
			"user@example.com",
			UserRole.user,
			UserStatus.active
		);
		when(validator.validate("access-token")).thenReturn(Optional.of(principal));
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setCookies(new MockCookie("access_token", "access-token"));
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
		assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(principal);
		verify(chain).doFilter(request, response);
	}

	@Test
	void doFilterSkipsAuthenticationWhenAccessCookieIsMissing() throws Exception {
		SessionTokenValidator validator = mock(SessionTokenValidator.class);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator);
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		verify(validator, never()).validate("access-token");
		verify(chain).doFilter(request, response);
	}

	@Test
	void doFilterIgnoresBrowserCookiesOnTheInternalAiCompletionCallback() throws Exception {
		SessionTokenValidator validator = mock(SessionTokenValidator.class);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator);
		MockHttpServletRequest request = new MockHttpServletRequest(
			"POST",
			"/api/v1/internal/ai/question-answer-jobs/10/completed"
		);
		request.setCookies(new MockCookie("access_token", "access-token"));
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(validator, never()).validate("access-token");
		verify(chain).doFilter(request, response);
	}
}
