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
	void doFilterSkipsAuthEndpoints() throws Exception {
		CsrfDoubleSubmitFilter filter = new CsrfDoubleSubmitFilter();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/logout");
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
