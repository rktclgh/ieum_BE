package shinhan.fibri.ieum.main.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CsrfDoubleSubmitFilterTest {

	@Test
	void doFilterRejectsUnsafeRequestWhenCsrfHeaderDoesNotMatchCookie() throws Exception {
		CsrfDoubleSubmitFilter filter = new CsrfDoubleSubmitFilter();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/users/me");
		request.setCookies(new MockCookie("csrf_token", "cookie-token"));
		request.addHeader("X-CSRF-Token", "other-token");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(response.getContentType()).isEqualTo("application/json");
		assertThat(response.getContentAsString()).contains("\"code\":\"CSRF_FAILED\"");
		verify(chain, never()).doFilter(request, response);
	}

	@Test
	void doFilterAllowsUnsafeRequestWhenCsrfHeaderMatchesCookie() throws Exception {
		CsrfDoubleSubmitFilter filter = new CsrfDoubleSubmitFilter();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/users/me");
		request.setCookies(new MockCookie("csrf_token", "csrf-token"));
		request.addHeader("X-CSRF-Token", "csrf-token");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
	}

	@Test
	void doFilterSkipsPreAuthBootstrapEndpoints() throws Exception {
		CsrfDoubleSubmitFilter filter = new CsrfDoubleSubmitFilter();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
		request.setServletPath("/api/v1/auth/login");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
	}

	@Test
	void doFilterSkipsSuspendedUserInquiryBootstrapEndpoint() throws Exception {
		CsrfDoubleSubmitFilter filter = new CsrfDoubleSubmitFilter();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/inquiries/suspended-users");
		request.setServletPath("/api/v1/inquiries/suspended-users");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
	}

	@Test
	void doFilterSkipsSocialAuthBootstrapEndpoint() throws Exception {
		CsrfDoubleSubmitFilter filter = new CsrfDoubleSubmitFilter();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/social");
		request.setServletPath("/api/v1/auth/social");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
	}

	@Test
	void doFilterSkipsSocialSignupBootstrapEndpoint() throws Exception {
		CsrfDoubleSubmitFilter filter = new CsrfDoubleSubmitFilter();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/social/signup");
		request.setServletPath("/api/v1/auth/social/signup");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
	}

	@Test
	void doFilterProtectsCookieBackedAuthEndpoints() throws Exception {
		CsrfDoubleSubmitFilter filter = new CsrfDoubleSubmitFilter();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/logout");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
		verify(chain, never()).doFilter(request, response);
	}

	@Test
	void doFilterProtectsNotificationStateChangeEndpoints() throws Exception {
		CsrfDoubleSubmitFilter filter = new CsrfDoubleSubmitFilter();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/notifications/read-all");
		request.setServletPath("/api/v1/notifications/read-all");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
		verify(chain, never()).doFilter(request, response);
	}

	@Test
	void doFilterSkipsOnlyTheExactAiCompletionCallbackRoute() throws Exception {
		CsrfDoubleSubmitFilter filter = new CsrfDoubleSubmitFilter();
		MockHttpServletRequest callback = new MockHttpServletRequest(
			"POST",
			"/api/v1/internal/ai/question-answer-jobs/10/completed"
		);
		MockHttpServletResponse callbackResponse = new MockHttpServletResponse();
		FilterChain callbackChain = mock(FilterChain.class);

		filter.doFilter(callback, callbackResponse, callbackChain);

		verify(callbackChain).doFilter(callback, callbackResponse);

		MockHttpServletRequest nearMiss = new MockHttpServletRequest(
			"POST",
			"/api/v1/internal/ai/question-answer-jobs/10/retry"
		);
		MockHttpServletResponse nearMissResponse = new MockHttpServletResponse();
		FilterChain nearMissChain = mock(FilterChain.class);

		filter.doFilter(nearMiss, nearMissResponse, nearMissChain);

		assertThat(nearMissResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
		verify(nearMissChain, never()).doFilter(nearMiss, nearMissResponse);
	}

	@Test
	void doFilterSkipsAiCompletionCallbackBehindContextPath() throws Exception {
		CsrfDoubleSubmitFilter filter = new CsrfDoubleSubmitFilter();
		MockHttpServletRequest request = new MockHttpServletRequest(
			"POST",
			"/ieum/api/v1/internal/ai/question-answer-jobs/10/completed"
		);
		request.setContextPath("/ieum");
		request.setServletPath("/api/v1/internal/ai/question-answer-jobs/10/completed");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
	}

	@Test
	void doFilterRejectsBlankCsrfTokenPair() throws Exception {
		CsrfDoubleSubmitFilter filter = new CsrfDoubleSubmitFilter();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/users/me");
		request.setCookies(new MockCookie("csrf_token", " "));
		request.addHeader("X-CSRF-Token", " ");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
		verify(chain, never()).doFilter(request, response);
	}

	@Test
	void doFilterMatchesBootstrapEndpointWithoutContextPath() throws Exception {
		CsrfDoubleSubmitFilter filter = new CsrfDoubleSubmitFilter();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ieum/api/v1/auth/login");
		request.setContextPath("/ieum");
		request.setServletPath("/api/v1/auth/login");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
	}

	@Test
	void doFilterSkipsSafeMethods() throws Exception {
		CsrfDoubleSubmitFilter filter = new CsrfDoubleSubmitFilter();
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
	}
}
