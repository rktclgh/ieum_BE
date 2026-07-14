package shinhan.fibri.ieum.main.auth.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import shinhan.fibri.ieum.main.notification.internal.InternalAiCallbackEndpoint;

@Component
public class CsrfDoubleSubmitFilter extends OncePerRequestFilter {

	private static final String CSRF_COOKIE_NAME = "csrf_token";
	private static final String CSRF_HEADER_NAME = "X-CSRF-Token";
	private static final Set<String> CSRF_BOOTSTRAP_ENDPOINTS = Set.of(
		"/api/v1/auth/email/send-code",
		"/api/v1/auth/email/verify",
		"/api/v1/auth/email/check-duplicate",
		"/api/v1/auth/nickname/check-duplicate",
		"/api/v1/auth/signup",
		"/api/v1/auth/login",
		"/api/v1/inquiries/suspended-users",
		"/api/v1/auth/social",
		"/api/v1/auth/social/signup"
	);

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
		return isSafeMethod(request.getMethod())
			|| CSRF_BOOTSTRAP_ENDPOINTS.contains(requestPath(request))
			|| InternalAiCallbackEndpoint.matches(request);
	}

	private boolean isSafeMethod(String method) {
		return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method) || "TRACE".equals(method);
	}

	private String requestPath(HttpServletRequest request) {
		String servletPath = request.getServletPath();
		if (servletPath != null && !servletPath.isBlank()) {
			return servletPath;
		}
		String contextPath = request.getContextPath();
		String requestUri = request.getRequestURI();
		if (contextPath != null && !contextPath.isBlank() && requestUri.startsWith(contextPath)) {
			return requestUri.substring(contextPath.length());
		}
		return requestUri;
	}

	private boolean hasMatchingCsrfToken(HttpServletRequest request) {
		String csrfCookie = csrfCookieValue(request);
		String csrfHeader = request.getHeader(CSRF_HEADER_NAME);
		if (csrfCookie == null || csrfHeader == null || csrfCookie.isBlank() || csrfHeader.isBlank()) {
			return false;
		}
		return MessageDigest.isEqual(
			csrfCookie.getBytes(StandardCharsets.UTF_8),
			csrfHeader.getBytes(StandardCharsets.UTF_8)
		);
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
