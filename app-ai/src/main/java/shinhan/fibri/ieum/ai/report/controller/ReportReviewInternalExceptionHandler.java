package shinhan.fibri.ieum.ai.report.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import shinhan.fibri.ieum.ai.report.dto.ReportReviewErrorResponse;
import shinhan.fibri.ieum.ai.report.service.InvalidReportReviewRequestException;
import shinhan.fibri.ieum.ai.report.service.ReportEvidenceImageDownloadException;
import shinhan.fibri.ieum.ai.report.service.ReportReviewModelGatewayException;

@RestControllerAdvice(assignableTypes = ReportReviewInternalController.class)
public class ReportReviewInternalExceptionHandler {

	@ExceptionHandler(InvalidReportReviewRequestException.class)
	ResponseEntity<ReportReviewErrorResponse> invalidRequest() {
		return error(HttpStatus.BAD_REQUEST, "invalid_report_review_request", false);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ReportReviewErrorResponse> unreadableRequest() {
		return error(HttpStatus.BAD_REQUEST, "invalid_report_review_request", false);
	}

	@ExceptionHandler(ReportEvidenceImageDownloadException.class)
	ResponseEntity<ReportReviewErrorResponse> imageDownloadFailure() {
		return error(HttpStatus.SERVICE_UNAVAILABLE, "report_image_download_failed", true);
	}

	@ExceptionHandler(ReportReviewModelGatewayException.class)
	ResponseEntity<ReportReviewErrorResponse> modelInferenceFailure() {
		return error(HttpStatus.SERVICE_UNAVAILABLE, "report_model_inference_failed", true);
	}

	private ResponseEntity<ReportReviewErrorResponse> error(HttpStatus status, String code, boolean retryable) {
		return ResponseEntity.status(status).body(new ReportReviewErrorResponse(code, retryable));
	}
}
