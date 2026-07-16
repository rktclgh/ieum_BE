package shinhan.fibri.ieum.ai.report.support;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.ai.report.service.ReportReviewProviderAttempt;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewRequest;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewResponse;

@Component
public class ReportReviewObservationLogger {

	private static final Logger log = LoggerFactory.getLogger(ReportReviewObservationLogger.class);
	private static final String START_NANOS_ATTRIBUTE = ReportReviewObservationLogger.class.getName() + ".startNanos";
	private static final String REPORT_ID_ATTRIBUTE = ReportReviewObservationLogger.class.getName() + ".reportId";
	private static final String ATTEMPT_ID_ATTRIBUTE = ReportReviewObservationLogger.class.getName() + ".attemptId";
	private static final Pattern REPORT_REVIEW_PATH = Pattern.compile("/reports/(\\d+)/review(?:$|[/?#])");

	public void received(HttpServletRequest servletRequest, long reportId, ReportReviewRequest request) {
		servletRequest.setAttribute(START_NANOS_ATTRIBUTE, System.nanoTime());
		servletRequest.setAttribute(REPORT_ID_ATTRIBUTE, reportId);
		servletRequest.setAttribute(ATTEMPT_ID_ATTRIBUTE, reviewAttemptId(request));

		log.info(
			"event=report_review_received reportId={} reviewAttemptId={}",
			reportId,
			reviewAttemptId(request)
		);
	}

	public void processingStarted(HttpServletRequest servletRequest, long reportId, ReportReviewRequest request) {
		log.info(
			"event=report_review_processing_started reportId={} reviewAttemptId={}",
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
			"event=report_review_complete reportId={} reviewAttemptId={} decision={} fallbackUsed={} providerAttempts={} durationMs={}",
			reportId,
			reviewAttemptId(request),
			valueOrUnknown(response == null ? null : response.decision()),
			valueOrUnknown(response == null ? null : response.fallbackUsed()),
			providerAttempts(response),
			durationMs(servletRequest)
		);
	}

	public void failed(HttpServletRequest servletRequest, String errorCode) {
		failed(servletRequest, errorCode, List.of());
	}

	public void failed(
		HttpServletRequest servletRequest,
		String errorCode,
		List<ReportReviewProviderAttempt> providerAttempts
	) {
		if (providerAttempts == null || providerAttempts.isEmpty()) {
			log.warn(
				"event=report_review_failure reportId={} reviewAttemptId={} errorCode={} durationMs={}",
				reportId(servletRequest),
				valueOrUnknown(servletRequest.getAttribute(ATTEMPT_ID_ATTRIBUTE)),
				errorCode,
				durationMs(servletRequest)
			);
			return;
		}
		log.warn(
			"event=report_review_failure reportId={} reviewAttemptId={} errorCode={} providerAttempts={} durationMs={}",
			reportId(servletRequest),
			valueOrUnknown(servletRequest.getAttribute(ATTEMPT_ID_ATTRIBUTE)),
			errorCode,
			providerAttempts(providerAttempts),
			durationMs(servletRequest)
		);
	}

	private static String providerAttempts(List<ReportReviewProviderAttempt> attempts) {
		return attempts.stream()
			.map(attempt -> "%s/%s/%s/%s/%d".formatted(
				attempt.provider(),
				attempt.model(),
				attempt.outcome(),
				attempt.errorCode() == null ? "none" : attempt.errorCode().name(),
				attempt.latencyMs()
			))
			.collect(java.util.stream.Collectors.joining(",", "[", "]"));
	}

	private static String providerAttempts(ReportReviewResponse response) {
		JsonNode attempts = response == null ? null : response.providerAttempts();
		if (attempts == null || !attempts.isArray()) {
			return "[]";
		}
		StringJoiner values = new StringJoiner(",", "[", "]");
		for (JsonNode attempt : attempts) {
			values.add("%s/%s/%s/%s/%d".formatted(
				attempt.path("provider").asText("unknown"),
				attempt.path("model").asText("unknown"),
				attempt.path("outcome").asText("unknown"),
				!attempt.hasNonNull("errorCode") ? "none" : attempt.path("errorCode").asText("unknown"),
				Math.max(0L, attempt.path("latencyMs").asLong(0L))
			));
		}
		return values.toString();
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
