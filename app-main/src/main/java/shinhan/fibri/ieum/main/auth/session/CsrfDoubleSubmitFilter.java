package shinhan.fibri.ieum.main.auth.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CsrfDoubleSubmitFilter extends OncePerRequestFilter {

	private static final String CSRF_COOKIE_NAME = "csrf_token";
	private static final String CSRF_HEADER_NAME = "X-CSRF-Token";

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		if (shouldSkip(request) || hasMatchingCsrfToken(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		response.setStatus(HttpStatus.FORBIDDEN.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"code\":\"CSRF_FAILED\",\"message\":\"CSRF validation failed\"}");
	}

	private boolean shouldSkip(HttpServletRequest request) {
		return isSafeMethod(request.getMethod()) || request.getRequestURI().startsWith("/api/v1/auth/");
	}

	private boolean isSafeMethod(String method) {
		return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method) || "TRACE".equals(method);
	}

	private boolean hasMatchingCsrfToken(HttpServletRequest request) {
		String csrfCookie = csrfCookieValue(request);
		String csrfHeader = request.getHeader(CSRF_HEADER_NAME);
		return csrfCookie != null && csrfCookie.equals(csrfHeader);
	}

	private String csrfCookieValue(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (CSRF_COOKIE_NAME.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}
}
