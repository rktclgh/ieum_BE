package shinhan.fibri.ieum.main.support;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;

public final class HttpRequestPaths {

	private static final List<String> BACKEND_PREFIXES = List.of(
		"/api",
		"/actuator",
		"/ws",
		"/swagger-ui",
		"/v3/api-docs"
	);
	private static final Set<String> BACKEND_EXACT_PATHS = Set.of(
		"/swagger-ui.html",
		"/v3/api-docs.yaml"
	);

	private HttpRequestPaths() {
	}

	public static String withinApplication(HttpServletRequest request) {
		String servletPath = request.getServletPath();
		if (servletPath != null && !servletPath.isBlank()) {
			return servletPath;
		}

		String requestUri = request.getRequestURI();
		String contextPath = request.getContextPath();
		if (contextPath != null && !contextPath.isBlank() && requestUri.startsWith(contextPath)) {
			String path = requestUri.substring(contextPath.length());
			return path.isEmpty() ? "/" : path;
		}
		return requestUri;
	}

	public static boolean isBackendOrOperations(HttpServletRequest request) {
		return isBackendOrOperations(withinApplication(request));
	}

	public static boolean isBackendOrOperations(String path) {
		return BACKEND_EXACT_PATHS.contains(path)
			|| BACKEND_PREFIXES.stream().anyMatch(prefix -> isSameOrChildPath(path, prefix));
	}

	private static boolean isSameOrChildPath(String path, String prefix) {
		return path.equals(prefix) || path.startsWith(prefix + "/");
	}
}
