package shinhan.fibri.ieum.main.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
		when(validator.validateSession("access-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(principal, "session-42")));
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setCookies(new MockCookie("access_token", "access-token"));
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
		assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(principal);
		assertThat(SecurityContextHolder.getContext().getAuthentication().getDetails())
			.isEqualTo(new AuthenticatedSessionDetails("session-42"));
		verify(chain).doFilter(request, response);
	}

	@Test
	void doFilterAuthenticatesBackendAsyncRedispatchWithValidAccessCookie() throws Exception {
		SessionTokenValidator validator = mock(SessionTokenValidator.class);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator);
		AuthenticatedUser principal = new AuthenticatedUser(
			42L,
			"user@example.com",
			UserRole.user,
			UserStatus.active
		);
		when(validator.validateSession("access-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(principal, "session-42")));
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files/1");
		request.setDispatcherType(DispatcherType.ASYNC);
		request.setCookies(new MockCookie("access_token", "access-token"));
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
		assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(principal);
		assertThat(SecurityContextHolder.getContext().getAuthentication().getDetails())
			.isEqualTo(new AuthenticatedSessionDetails("session-42"));
		verify(validator).validateSession("access-token");
		verify(chain).doFilter(request, response);
	}

	@Test
	void doFilterLeavesNoAuthenticationDetailsWhenSessionIsInvalid() throws Exception {
		SessionTokenValidator validator = mock(SessionTokenValidator.class);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator);
		when(validator.validateSession("invalid-token")).thenReturn(Optional.empty());
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setCookies(new MockCookie("access_token", "invalid-token"));
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		verify(validator).validateSession("invalid-token");
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
		verify(validator, never()).validateSession("access-token");
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

		verify(validator, never()).validateSession("access-token");
		verify(chain).doFilter(request, response);
	}

	@Test
	void doFilterIgnoresInternalAiCompletionCallbackBehindContextPath() throws Exception {
		SessionTokenValidator validator = mock(SessionTokenValidator.class);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator);
		MockHttpServletRequest request = new MockHttpServletRequest(
			"POST",
			"/ieum/api/v1/internal/ai/question-answer-jobs/10/completed"
		);
		request.setContextPath("/ieum");
		request.setServletPath("/api/v1/internal/ai/question-answer-jobs/10/completed");
		request.setCookies(new MockCookie("access_token", "access-token"));
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(validator, never()).validateSession("access-token");
		verify(chain).doFilter(request, response);
	}

	@ParameterizedTest
	@ValueSource(strings = {"GET", "HEAD"})
	void doFilterSkipsAuthenticationForSafeFrontendReads(String method) throws Exception {
		SessionTokenValidator validator = mock(SessionTokenValidator.class);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator);
		MockHttpServletRequest request = new MockHttpServletRequest(method, "/login/");
		request.setCookies(new MockCookie("access_token", "access-token"));
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(validator, never()).validateSession("access-token");
		verify(chain).doFilter(request, response);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/swagger-ui.html.evil", "/v3/api-docs.yaml.evil"})
	void backendExactPathNearMissRemainsAFrontendRead(String path) throws Exception {
		SessionTokenValidator validator = mock(SessionTokenValidator.class);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		request.setCookies(new MockCookie("access_token", "access-token"));
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(validator, never()).validateSession("access-token");
		verify(chain).doFilter(request, response);
	}

	@Test
	void doFilterStillAuthenticatesUnsafeFrontendRequests() throws Exception {
		SessionTokenValidator validator = mock(SessionTokenValidator.class);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator);
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login/");
		request.setCookies(new MockCookie("access_token", "access-token"));
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(validator).validateSession("access-token");
		verify(chain).doFilter(request, response);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"/api/v1/protected/ping",
		"/actuator/info",
		"/ws/chat",
		"/swagger-ui/index.html",
		"/swagger-ui.html",
		"/v3/api-docs",
		"/v3/api-docs.yaml"
	})
	void doFilterStillAuthenticatesSafeReadsOnBackendAndOperationsPaths(String path) throws Exception {
		SessionTokenValidator validator = mock(SessionTokenValidator.class);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		request.setCookies(new MockCookie("access_token", "access-token"));
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(validator).validateSession("access-token");
		verify(chain).doFilter(request, response);
	}

	@Test
	void doFilterRecognizesBackendPathBehindContextPath() throws Exception {
		SessionTokenValidator validator = mock(SessionTokenValidator.class);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ieum/api/v1/protected/ping");
		request.setContextPath("/ieum");
		request.setCookies(new MockCookie("access_token", "access-token"));
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(validator).validateSession("access-token");
		verify(chain).doFilter(request, response);
	}
}
