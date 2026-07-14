package shinhan.fibri.ieum.main.notification.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import shinhan.fibri.ieum.main.support.HttpRequestPaths;

public final class InternalAiCallbackEndpoint {

	public static final String SECURITY_PATTERN =
		"/api/v1/internal/ai/question-answer-jobs/*/completed";
	private static final Pattern PATH = Pattern.compile(
		"^/api/v1/internal/ai/question-answer-jobs/[1-9][0-9]*/completed$"
	);

	private InternalAiCallbackEndpoint() {
	}

	public static boolean matches(HttpServletRequest request) {
		return "POST".equals(request.getMethod())
			&& PATH.matcher(HttpRequestPaths.withinApplication(request)).matches();
	}
}
