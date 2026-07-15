package shinhan.fibri.ieum.ai.report.support;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewRequest;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewResponse;

@Component
public class ReportReviewObservationLogger {

	private static final Logger log = LoggerFactory.getLogger(ReportReviewObservationLogger.class);
	private static final String START_NANOS_ATTRIBUTE = ReportReviewObservationLogger.class.getName() + ".startNanos";
	private static final String REPORT_ID_ATTRIBUTE = ReportReviewObservationLogger.class.getName() + ".reportId";
	private static final String ATTEMPT_ID_ATTRIBUTE = ReportReviewObservationLogger.class.getName() + ".attemptId";
	private static final Pattern REPORT_REVIEW_PATH = Pattern.compile("/reports/(\\d+)/review(?:$|[/?#])");

	public void started(HttpServletRequest servletRequest, long reportId, ReportReviewRequest request) {
		servletRequest.setAttribute(START_NANOS_ATTRIBUTE, System.nanoTime());
		servletRequest.setAttribute(REPORT_ID_ATTRIBUTE, reportId);
		servletRequest.setAttribute(ATTEMPT_ID_ATTRIBUTE, reviewAttemptId(request));

		log.info(
			"event=report_review_start reportId={} reviewAttemptId={}",
			reportId,
			reviewAttemptId(request)
		);
	}

	public void completed(
		HttpServletRequest servletRequest,
		long reportId,
		ReportReviewRequest request,
		ReportReviewResponse response
	) {
		log.info(
			"event=report_review_complete reportId={} reviewAttemptId={} decision={} fallbackUsed={} durationMs={}",
			reportId,
			reviewAttemptId(request),
			valueOrUnknown(response == null ? null : response.decision()),
			valueOrUnknown(response == null ? null : response.fallbackUsed()),
			durationMs(servletRequest)
		);
	}

	public void failed(HttpServletRequest servletRequest, String errorCode) {
		log.warn(
			"event=report_review_failure reportId={} reviewAttemptId={} errorCode={} durationMs={}",
			reportId(servletRequest),
			valueOrUnknown(servletRequest.getAttribute(ATTEMPT_ID_ATTRIBUTE)),
			errorCode,
			durationMs(servletRequest)
		);
	}

	private static Object reviewAttemptId(ReportReviewRequest request) {
		UUID reviewAttemptId = request == null ? null : request.reviewAttemptId();
		return valueOrUnknown(reviewAttemptId);
	}

	private static Object reportId(HttpServletRequest servletRequest) {
		Object reportId = servletRequest.getAttribute(REPORT_ID_ATTRIBUTE);
		if (reportId != null) {
			return reportId;
		}

		Matcher matcher = REPORT_REVIEW_PATH.matcher(servletRequest.getRequestURI());
		if (matcher.find()) {
			return matcher.group(1);
		}
		return "unknown";
	}

	private static long durationMs(HttpServletRequest servletRequest) {
		Object startNanos = servletRequest.getAttribute(START_NANOS_ATTRIBUTE);
		if (!(startNanos instanceof Long start)) {
			return 0L;
		}
		return Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
	}

	private static Object valueOrUnknown(Object value) {
		return value == null ? "unknown" : value;
	}
}
