package shinhan.fibri.ieum.ai.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import shinhan.fibri.ieum.ai.report.support.ReportReviewObservationLogger;
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

@ExtendWith(OutputCaptureExtension.class)
class ReportReviewInternalControllerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final ReportReviewPreparationService preparationService = mock(ReportReviewPreparationService.class);
	private final ReportReviewInferenceOrchestrator inferenceOrchestrator = mock(ReportReviewInferenceOrchestrator.class);
	private final ReportReviewObservationLogger observationLogger = new ReportReviewObservationLogger();

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(new ReportReviewInternalController(
				preparationService,
				inferenceOrchestrator,
				observationLogger,
				objectMapper
			))
			.setControllerAdvice(new ReportReviewInternalExceptionHandler(observationLogger))
			.build();
	}

	@Test
	void reviewsTheImmutableRequestAndReturnsTheSharedResponse(CapturedOutput output) throws Exception {
		ReportReviewRequest request = request();
		PreparedReportReview prepared = preparedReview();
		when(preparationService.prepare(900L, request)).thenReturn(prepared);
		when(inferenceOrchestrator.review(same(prepared))).thenReturn(response());

		mockMvc.perform(post("/ai/v1/internal/reports/900/review")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(request)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.decision").value("hold"))
			.andExpect(jsonPath("$.evidence[0].messageId").value(2))
			.andExpect(jsonPath("$.matchedRules[0].ruleCode").value("HARASSMENT-CONTEXTUAL-001"))
			.andExpect(jsonPath("$.policySnapshot.rules[0].ruleCode").value("HARASSMENT-CONTEXTUAL-001"))
			.andExpect(jsonPath("$.providerAttempts[0].provider").value("bedrock"))
			.andExpect(jsonPath("$.modelVersion").value("amazon.nova-lite-v1:0"))
			.andExpect(jsonPath("$.fallbackUsed").value(false));

		verify(preparationService).prepare(900L, request);
		verify(inferenceOrchestrator).review(prepared);
		assertSafeSuccessLogs(output);
	}

	@Test
	void returnsABadRequestEnvelopeWithoutTheValidationMessage(CapturedOutput output) throws Exception {
		doThrow(new InvalidReportReviewRequestException("request detail must not leak"))
			.when(preparationService).prepare(900L, request());

		mockMvc.perform(post("/ai/v1/internal/reports/900/review")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(request())))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_report_review_request"))
			.andExpect(jsonPath("$.retryable").value(false))
			.andExpect(jsonPath("$.message").doesNotExist());

		assertSafeFailureLog(output, "invalid_report_review_request");
		assertThat(output).doesNotContain("request detail must not leak");
	}

	@Test
	void returnsTheBadRequestEnvelopeForANullJsonRequest(CapturedOutput output) throws Exception {
		doThrow(new InvalidReportReviewRequestException("null request must not leak"))
			.when(preparationService).prepare(900L, null);

		mockMvc.perform(post("/ai/v1/internal/reports/900/review")
				.contentType(MediaType.APPLICATION_JSON)
				.content("null"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_report_review_request"))
			.andExpect(jsonPath("$.retryable").value(false));

		assertSafeFailureLog(output, "invalid_report_review_request");
		assertThat(output).doesNotContain("null request must not leak");
	}

	@Test
	void returnsTheBadRequestEnvelopeForMissingOrMalformedJson(CapturedOutput output) throws Exception {
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

		assertSafeFailureLog(output, "invalid_report_review_request");
		assertThat(output).doesNotContain("{invalid");
	}

	@Test
	void returnsARetryableEnvelopeForImageDownloadFailure(CapturedOutput output) throws Exception {
		doThrow(new ReportEvidenceImageDownloadException("presigned query must not leak"))
			.when(preparationService).prepare(900L, request());

		mockMvc.perform(post("/ai/v1/internal/reports/900/review")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(request())))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("report_image_download_failed"))
			.andExpect(jsonPath("$.retryable").value(true));

		assertSafeFailureLog(output, "report_image_download_failed");
		assertThat(output).doesNotContain("presigned query must not leak");
	}

	@Test
	void returnsARetryableEnvelopeForModelFailure(CapturedOutput output) throws Exception {
		PreparedReportReview prepared = preparedReview();
		when(preparationService.prepare(900L, request())).thenReturn(prepared);
		doThrow(new ReportReviewModelGatewayException()).when(inferenceOrchestrator).review(prepared);

		mockMvc.perform(post("/ai/v1/internal/reports/900/review")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(request())))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("report_model_inference_failed"))
			.andExpect(jsonPath("$.retryable").value(true));

		assertSafeFailureLog(output, "report_model_inference_failed");
	}

	@Test
	void preservesTheSerializationFailureAsTheGatewayExceptionCause() throws Exception {
		ObjectMapper failingObjectMapper = mock(ObjectMapper.class);
		ReportReviewObservationLogger logger = mock(ReportReviewObservationLogger.class);
		ReportReviewInternalController controller = new ReportReviewInternalController(
			preparationService,
			inferenceOrchestrator,
			logger,
			failingObjectMapper
		);
		ReportReviewRequest request = request();
		PreparedReportReview prepared = preparedReview();
		ReportReviewResponse response = response();
		JsonProcessingException serializationFailure = new JsonProcessingException("serialization failed") {
		};
		when(preparationService.prepare(900L, request)).thenReturn(prepared);
		when(inferenceOrchestrator.review(prepared)).thenReturn(response);
		when(failingObjectMapper.writeValueAsBytes(response)).thenThrow(serializationFailure);

		assertThatThrownBy(() -> controller.review(900L, request, mock(HttpServletRequest.class)))
			.isInstanceOf(ReportReviewModelGatewayException.class)
			.hasCause(serializationFailure);
	}

	private void assertSafeSuccessLogs(CapturedOutput output) {
		assertThat(output)
			.contains("event=report_review_start")
			.contains("reportId=900")
			.contains("reviewAttemptId=123e4567-e89b-12d3-a456-426614174000")
			.contains("event=report_review_complete")
			.contains("decision=hold")
			.contains("fallbackUsed=false")
			.containsPattern("durationMs=\\d+");
		assertThat(output)
			.doesNotContain("detail")
			.doesNotContain("content")
			.doesNotContain("reason");
	}

	private void assertSafeFailureLog(CapturedOutput output, String errorCode) {
		String failureLog = output.getOut()
			.lines()
			.filter(line -> line.contains("event=report_review_failure"))
			.reduce((first, second) -> second)
			.orElse("");

		assertThat(failureLog)
			.contains("event=report_review_failure")
			.contains("reportId=900")
			.contains("reviewAttemptId=")
			.contains("errorCode=" + errorCode)
			.containsPattern("durationMs=\\d+");
		assertThat(failureLog)
			.doesNotContain("decision=")
			.doesNotContain("fallbackUsed=");
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
		var evidence = objectMapper.createArrayNode();
		evidence.addObject().put("messageId", 2L).put("type", "text");
		var matchedRules = objectMapper.createArrayNode();
		matchedRules.addObject().put("ruleCode", "HARASSMENT-CONTEXTUAL-001").put("revision", 1);
		var policySnapshot = objectMapper.createObjectNode();
		policySnapshot.putArray("rules").addObject()
			.put("ruleCode", "HARASSMENT-CONTEXTUAL-001")
			.put("revision", 1);
		var providerAttempts = objectMapper.createArrayNode();
		providerAttempts.addObject().put("provider", "bedrock").put("outcome", "success");
		return new ReportReviewResponse(
			"hold", "harassment", "medium", new BigDecimal("0.91"), "reason",
			evidence, matchedRules, "b".repeat(64), policySnapshot,
			"amazon.nova-lite-v1:0", "report-review-v1", false, providerAttempts
		);
	}
}
