package shinhan.fibri.ieum.main.report.ai.service;

import java.time.Duration;
import java.util.List;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

public class ReportAiRetryPolicy {

	private static final List<Duration> BACKOFFS = List.of(
		Duration.ofSeconds(10),
		Duration.ofSeconds(30),
		Duration.ofMinutes(2),
		Duration.ofMinutes(10)
	);

	ReportAiFailureDisposition classify(RuntimeException failure, int attempts, int maxAttempts) {
		if (failure instanceof ReportAiPermanentException permanent) {
			return new ReportAiFailureDisposition(permanent.errorCode(), null);
		}
		if (failure instanceof HttpMessageConversionException) {
			return new ReportAiFailureDisposition("REPORT_AI_RESPONSE_INVALID", null);
		}
		String errorCode = errorCode(failure);
		if (attempts >= maxAttempts) {
			return new ReportAiFailureDisposition("REPORT_AI_MAX_ATTEMPTS", null);
		}
		if (!retryable(failure)) {
			return new ReportAiFailureDisposition(errorCode, null);
		}
		int backoffIndex = Math.min(Math.max(attempts - 1, 0), BACKOFFS.size() - 1);
		return new ReportAiFailureDisposition(errorCode, BACKOFFS.get(backoffIndex));
	}

	private boolean retryable(RuntimeException failure) {
		if (failure instanceof ResourceAccessException) {
			return true;
		}
		if (failure instanceof RestClientResponseException responseFailure) {
			int status = responseFailure.getStatusCode().value();
			return status == 429 || status >= 500;
		}
		return true;
	}

	private String errorCode(RuntimeException failure) {
		if (failure instanceof ResourceAccessException) {
			return "REPORT_AI_TRANSPORT_FAILURE";
		}
		if (failure instanceof RestClientResponseException responseFailure) {
			int status = responseFailure.getStatusCode().value();
			if (status == 429) {
				return "REPORT_AI_RATE_LIMITED";
			}
			return status >= 500 ? "REPORT_AI_SERVICE_FAILURE" : "REPORT_AI_REQUEST_REJECTED";
		}
		return "REPORT_AI_UNEXPECTED_FAILURE";
	}
}
