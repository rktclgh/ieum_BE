package shinhan.fibri.ieum.main.auth.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.notification.internal.InternalAiCallbackEndpoint;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final SessionTokenValidator sessionTokenValidator;

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return InternalAiCallbackEndpoint.matches(request);
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		findAccessToken(request)
			.flatMap(sessionTokenValidator::validate)
			.ifPresent(this::setAuthentication);
		filterChain.doFilter(request, response);
	}

	private java.util.Optional<String> findAccessToken(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return java.util.Optional.empty();
		}
		return Arrays.stream(cookies)
			.filter(cookie -> "access_token".equals(cookie.getName()))
			.map(Cookie::getValue)
			.findFirst();
	}

	private void setAuthentication(AuthenticatedUser principal) {
		UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
			principal,
			null,
			List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))
		);
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}
}
