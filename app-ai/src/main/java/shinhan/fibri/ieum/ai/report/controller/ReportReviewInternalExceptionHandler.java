package shinhan.fibri.ieum.ai.report.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import shinhan.fibri.ieum.ai.report.dto.ReportReviewErrorResponse;
import shinhan.fibri.ieum.ai.report.support.ReportReviewObservationLogger;
import shinhan.fibri.ieum.ai.report.service.InvalidReportReviewRequestException;
import shinhan.fibri.ieum.ai.report.service.ReportEvidenceImageDownloadException;
import shinhan.fibri.ieum.ai.report.service.ReportReviewModelGatewayException;

@RestControllerAdvice(assignableTypes = ReportReviewInternalController.class)
public class ReportReviewInternalExceptionHandler {

	private final ReportReviewObservationLogger observationLogger;

	public ReportReviewInternalExceptionHandler(ReportReviewObservationLogger observationLogger) {
		this.observationLogger = observationLogger;
	}

	@ExceptionHandler(InvalidReportReviewRequestException.class)
	ResponseEntity<ReportReviewErrorResponse> invalidRequest(HttpServletRequest servletRequest) {
		return error(servletRequest, HttpStatus.BAD_REQUEST, "invalid_report_review_request", false);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ReportReviewErrorResponse> unreadableRequest(HttpServletRequest servletRequest) {
		return error(servletRequest, HttpStatus.BAD_REQUEST, "invalid_report_review_request", false);
	}

	@ExceptionHandler(ReportEvidenceImageDownloadException.class)
	ResponseEntity<ReportReviewErrorResponse> imageDownloadFailure(HttpServletRequest servletRequest) {
		return error(servletRequest, HttpStatus.SERVICE_UNAVAILABLE, "report_image_download_failed", true);
	}

	@ExceptionHandler(ReportReviewModelGatewayException.class)
	ResponseEntity<ReportReviewErrorResponse> modelInferenceFailure(
		HttpServletRequest servletRequest,
		ReportReviewModelGatewayException exception
	) {
		observationLogger.failed(
			servletRequest,
			"report_model_inference_failed",
			exception.providerAttempts()
		);
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.body(new ReportReviewErrorResponse("report_model_inference_failed", true));
	}

	private ResponseEntity<ReportReviewErrorResponse> error(
		HttpServletRequest servletRequest,
		HttpStatus status,
		String code,
		boolean retryable
	) {
		observationLogger.failed(servletRequest, code);
		return ResponseEntity.status(status).body(new ReportReviewErrorResponse(code, retryable));
	}
}
