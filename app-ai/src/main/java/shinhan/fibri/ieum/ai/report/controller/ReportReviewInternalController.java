package shinhan.fibri.ieum.ai.report.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.ai.report.support.ReportReviewObservationLogger;
import shinhan.fibri.ieum.ai.report.service.PreparedReportReview;
import shinhan.fibri.ieum.ai.report.service.ReportReviewInferenceOrchestrator;
import shinhan.fibri.ieum.ai.report.service.ReportReviewModelGatewayException;
import shinhan.fibri.ieum.ai.report.service.ReportReviewPreparationService;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewRequest;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewResponse;

@RestController
@RequestMapping("/ai/v1/internal/reports")
@ConditionalOnProperty(prefix = "app.ai.features", name = "report-review-enabled", havingValue = "true")
public class ReportReviewInternalController {

	private final ReportReviewPreparationService preparationService;
	private final ReportReviewInferenceOrchestrator inferenceOrchestrator;
	private final ReportReviewObservationLogger observationLogger;
	private final ObjectMapper objectMapper;

	public ReportReviewInternalController(
		ReportReviewPreparationService preparationService,
		ReportReviewInferenceOrchestrator inferenceOrchestrator,
		ReportReviewObservationLogger observationLogger,
		ObjectMapper objectMapper
	) {
		this.preparationService = preparationService;
		this.inferenceOrchestrator = inferenceOrchestrator;
		this.observationLogger = observationLogger;
		this.objectMapper = objectMapper;
	}

	@PostMapping("/{reportId}/review")
	public ResponseEntity<byte[]> review(
		@PathVariable long reportId,
		@RequestBody ReportReviewRequest request,
		HttpServletRequest servletRequest
	) {
		observationLogger.started(servletRequest, reportId, request);
		PreparedReportReview preparedReview = preparationService.prepare(reportId, request);
		ReportReviewResponse response = inferenceOrchestrator.review(preparedReview);
		byte[] responseBody = serialize(response);
		observationLogger.completed(servletRequest, reportId, request, response);
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.body(responseBody);
	}

	private byte[] serialize(ReportReviewResponse response) {
		try {
			return objectMapper.writeValueAsBytes(response);
		} catch (JsonProcessingException exception) {
			throw new ReportReviewModelGatewayException(exception);
		}
	}
}
