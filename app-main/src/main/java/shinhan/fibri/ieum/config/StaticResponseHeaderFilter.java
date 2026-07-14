package shinhan.fibri.ieum.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import shinhan.fibri.ieum.main.support.HttpRequestPaths;

@Component
public class StaticResponseHeaderFilter extends OncePerRequestFilter {

	private static final String NO_CACHE = "no-cache";
	private static final String IMMUTABLE_CACHE = "public, max-age=31536000, immutable";
	private static final String SHORT_CACHE = "public, max-age=3600";
	private static final String OPENER_POLICY_HEADER = "Cross-Origin-Opener-Policy";
	private static final String OPENER_POLICY = "same-origin-allow-popups";

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String requestPath = requestPath(request);
		if (!isStaticRead(request) || HttpRequestPaths.isBackendOrOperations(requestPath)) {
			filterChain.doFilter(request, response);
			return;
		}

		StaticResponseHeaderWrapper wrappedResponse = new StaticResponseHeaderWrapper(response, requestPath);
		filterChain.doFilter(request, wrappedResponse);
		wrappedResponse.applyHeaders();
	}

	private boolean isStaticRead(HttpServletRequest request) {
		return "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
	}

	private static boolean isPathWithin(String requestPath, String prefix) {
		return prefix.equals(requestPath) || requestPath.startsWith(prefix + "/");
	}

	private String requestPath(HttpServletRequest request) {
		return HttpRequestPaths.withinApplication(request);
	}

	private static final class StaticResponseHeaderWrapper extends HttpServletResponseWrapper {

		private final String requestPath;

		private StaticResponseHeaderWrapper(HttpServletResponse response, String requestPath) {
			super(response);
			this.requestPath = requestPath;
			applyHeaders();
		}

		@Override
		public void setContentType(String type) {
			super.setContentType(type);
			applyHeaders();
		}

		@Override
		public void setStatus(int status) {
			super.setStatus(status);
			applyHeaders();
		}

		@Override
		public void setHeader(String name, String value) {
			super.setHeader(name, value);
			applyHeaders();
		}

		@Override
		public void addHeader(String name, String value) {
			super.addHeader(name, value);
			applyHeaders();
		}

		@Override
		public void sendError(int status) throws IOException {
			super.setStatus(status);
			applyHeaders();
			super.sendError(status);
		}

		@Override
		public void sendError(int status, String message) throws IOException {
			super.setStatus(status);
			applyHeaders();
			super.sendError(status, message);
		}

		@Override
		public void flushBuffer() throws IOException {
			applyHeaders();
			super.flushBuffer();
		}

		@Override
		public void reset() {
			super.reset();
			applyHeaders();
		}

		private void applyHeaders() {
			String contentType = getContentType();
			if (contentType != null
				&& contentType.toLowerCase(Locale.ROOT).startsWith(MediaType.TEXT_HTML_VALUE)) {
				super.setHeader(HttpHeaders.CACHE_CONTROL, NO_CACHE);
				super.setHeader(OPENER_POLICY_HEADER, OPENER_POLICY);
				return;
			}

			if (!isCacheableStatus()) {
				super.setHeader(HttpHeaders.CACHE_CONTROL, NO_CACHE);
				return;
			}
			if (isPathWithin(requestPath, "/_next/static")) {
				super.setHeader(HttpHeaders.CACHE_CONTROL, IMMUTABLE_CACHE);
				return;
			}
			if (isPathWithin(requestPath, "/icons")) {
				super.setHeader(HttpHeaders.CACHE_CONTROL, SHORT_CACHE);
				return;
			}
			if (requestPath.endsWith(".txt") || requestPath.endsWith(".webmanifest")) {
				super.setHeader(HttpHeaders.CACHE_CONTROL, NO_CACHE);
			}
		}

		private boolean isCacheableStatus() {
			int status = getStatus();
			return (status >= 200 && status < 300) || status == HttpServletResponse.SC_NOT_MODIFIED;
		}
	}
}
