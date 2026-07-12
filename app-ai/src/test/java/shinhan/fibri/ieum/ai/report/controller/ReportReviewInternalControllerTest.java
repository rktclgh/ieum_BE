package shinhan.fibri.ieum.ai.report.controller;

import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import shinhan.fibri.ieum.ai.report.service.InvalidReportReviewRequestException;
import shinhan.fibri.ieum.ai.report.service.PreparedReportReview;
import shinhan.fibri.ieum.ai.report.service.ReportEvidenceImageBatch;
import shinhan.fibri.ieum.ai.report.service.ReportEvidenceImageDownloadException;
import shinhan.fibri.ieum.ai.report.service.ReportReviewInferenceOrchestrator;
import shinhan.fibri.ieum.ai.report.service.ReportReviewModelGatewayException;
import shinhan.fibri.ieum.ai.report.service.ReportReviewPreparationService;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewMessage;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewRequest;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewResponse;

class ReportReviewInternalControllerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final ReportReviewPreparationService preparationService = mock(ReportReviewPreparationService.class);
	private final ReportReviewInferenceOrchestrator inferenceOrchestrator = mock(ReportReviewInferenceOrchestrator.class);

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(new ReportReviewInternalController(preparationService, inferenceOrchestrator))
			.setControllerAdvice(new ReportReviewInternalExceptionHandler())
			.build();
	}

	@Test
	void reviewsTheImmutableRequestAndReturnsTheSharedResponse() throws Exception {
		ReportReviewRequest request = request();
		PreparedReportReview prepared = preparedReview();
		when(preparationService.prepare(900L, request)).thenReturn(prepared);
		when(inferenceOrchestrator.review(same(prepared))).thenReturn(response());

		mockMvc.perform(post("/ai/v1/internal/reports/900/review")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(request)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.decision").value("hold"))
			.andExpect(jsonPath("$.modelVersion").value("amazon.nova-lite-v1:0"))
			.andExpect(jsonPath("$.fallbackUsed").value(false));

		verify(preparationService).prepare(900L, request);
		verify(inferenceOrchestrator).review(prepared);
	}

	@Test
	void returnsABadRequestEnvelopeWithoutTheValidationMessage() throws Exception {
		doThrow(new InvalidReportReviewRequestException("request detail must not leak"))
			.when(preparationService).prepare(900L, request());

		mockMvc.perform(post("/ai/v1/internal/reports/900/review")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(request())))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_report_review_request"))
			.andExpect(jsonPath("$.retryable").value(false))
			.andExpect(jsonPath("$.message").doesNotExist());
	}

	@Test
	void returnsTheBadRequestEnvelopeForANullJsonRequest() throws Exception {
		doThrow(new InvalidReportReviewRequestException("null request must not leak"))
			.when(preparationService).prepare(900L, null);

		mockMvc.perform(post("/ai/v1/internal/reports/900/review")
				.contentType(MediaType.APPLICATION_JSON)
				.content("null"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_report_review_request"))
			.andExpect(jsonPath("$.retryable").value(false));
	}

	@Test
	void returnsTheBadRequestEnvelopeForMissingOrMalformedJson() throws Exception {
		mockMvc.perform(post("/ai/v1/internal/reports/900/review")
				.contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_report_review_request"))
			.andExpect(jsonPath("$.retryable").value(false));

		mockMvc.perform(post("/ai/v1/internal/reports/900/review")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{invalid"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_report_review_request"))
			.andExpect(jsonPath("$.retryable").value(false));
	}

	@Test
	void returnsARetryableEnvelopeForImageDownloadFailure() throws Exception {
		doThrow(new ReportEvidenceImageDownloadException("presigned query must not leak"))
			.when(preparationService).prepare(900L, request());

		mockMvc.perform(post("/ai/v1/internal/reports/900/review")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(request())))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("report_image_download_failed"))
			.andExpect(jsonPath("$.retryable").value(true));
	}

	@Test
	void returnsARetryableEnvelopeForModelFailure() throws Exception {
		PreparedReportReview prepared = preparedReview();
		when(preparationService.prepare(900L, request())).thenReturn(prepared);
		doThrow(new ReportReviewModelGatewayException()).when(inferenceOrchestrator).review(prepared);

		mockMvc.perform(post("/ai/v1/internal/reports/900/review")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(request())))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("report_model_inference_failed"))
			.andExpect(jsonPath("$.retryable").value(true));
	}

	private ReportReviewRequest request() {
		return new ReportReviewRequest(
			900L,
			UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
			2L,
			"harassment",
			"detail",
			"a".repeat(64),
			List.of(new ReportReviewMessage(2L, "reported_user", "content", null, "2026-07-12T00:00:00Z"))
		);
	}

	private PreparedReportReview preparedReview() {
		return new PreparedReportReview(
			900L,
			UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
			2L,
			"harassment",
			"detail",
			"a".repeat(64),
			List.of(),
			new ReportEvidenceImageBatch(java.util.Map.of(), 0L)
		);
	}

	private ReportReviewResponse response() {
		return new ReportReviewResponse(
			"hold", "harassment", "medium", new BigDecimal("0.91"), "reason",
			objectMapper.createArrayNode(), objectMapper.createArrayNode(), "b".repeat(64), objectMapper.createObjectNode(),
			"amazon.nova-lite-v1:0", "report-review-v1", false, objectMapper.createArrayNode()
		);
	}
}
