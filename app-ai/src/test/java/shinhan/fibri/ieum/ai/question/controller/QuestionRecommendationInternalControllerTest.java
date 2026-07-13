package shinhan.fibri.ieum.ai.question.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import shinhan.fibri.ieum.ai.question.service.EmbeddingUnavailableException;
import shinhan.fibri.ieum.ai.question.service.QuestionRecommendationService;
import shinhan.fibri.ieum.common.ai.question.dto.InternalQuestionRecommendationItem;
import shinhan.fibri.ieum.common.ai.question.dto.InternalQuestionRecommendationRequest;
import shinhan.fibri.ieum.common.ai.question.dto.InternalQuestionRecommendationResponse;
import shinhan.fibri.ieum.common.ai.question.dto.QuestionRecommendationLocation;
import shinhan.fibri.ieum.common.ai.question.dto.RecommendedAcceptedAnswer;

class QuestionRecommendationInternalControllerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final QuestionRecommendationService recommendationService = mock(QuestionRecommendationService.class);

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(new QuestionRecommendationInternalController(recommendationService))
			.setControllerAdvice(new QuestionRecommendationInternalExceptionHandler())
			.build();
	}

	@Test
	void delegatesTheInternalRequestAndReturnsTheSharedResponse() throws Exception {
		InternalQuestionRecommendationRequest request = request();
		when(recommendationService.recommend(eq(request))).thenReturn(response());

		mockMvc.perform(post("/ai/v1/internal/questions/recommendations")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(request)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].questionId").value(3))
			.andExpect(jsonPath("$.items[0].authorId").value(44))
			.andExpect(jsonPath("$.items[0].title").value("한국 버스 승하차 방법"))
			.andExpect(jsonPath("$.items[0].relevanceScore").value(0.87))
			.andExpect(jsonPath("$.items[0].geoScope").value("general"))
			.andExpect(jsonPath("$.items[0].isResolved").value(true))
			.andExpect(jsonPath("$.items[0].acceptedAnswer.content").value("앞문 승차, 뒷문 하차가 일반적입니다."))
			.andExpect(jsonPath("$.items[0].acceptedAnswer.isAi").value(false));

		verify(recommendationService).recommend(eq(request));
	}

	@Test
	void returnsABadRequestEnvelopeForInvalidRequestsWithoutCallingTheService() throws Exception {
		InternalQuestionRecommendationRequest request = new InternalQuestionRecommendationRequest(
			" ",
			"한국에서는 어디로 타고 내려요?",
			location(),
			20
		);

		mockMvc.perform(post("/ai/v1/internal/questions/recommendations")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(request)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_question_recommendation_request"))
			.andExpect(jsonPath("$.retryable").value(false))
			.andExpect(jsonPath("$.message").doesNotExist());

		verifyNoInteractions(recommendationService);
	}

	@Test
	void returnsTheBadRequestEnvelopeForMissingNullOrMalformedJson() throws Exception {
		mockMvc.perform(post("/ai/v1/internal/questions/recommendations")
				.contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_question_recommendation_request"))
			.andExpect(jsonPath("$.retryable").value(false));

		mockMvc.perform(post("/ai/v1/internal/questions/recommendations")
				.contentType(MediaType.APPLICATION_JSON)
				.content("null"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_question_recommendation_request"))
			.andExpect(jsonPath("$.retryable").value(false));

		mockMvc.perform(post("/ai/v1/internal/questions/recommendations")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{invalid"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_question_recommendation_request"))
			.andExpect(jsonPath("$.retryable").value(false));

		verifyNoInteractions(recommendationService);
	}

	@Test
	void returnsARetryableServiceUnavailableEnvelopeForEmbeddingFailures() throws Exception {
		InternalQuestionRecommendationRequest request = request();
		doThrow(new EmbeddingUnavailableException("provider detail must not leak"))
			.when(recommendationService).recommend(eq(request));

		mockMvc.perform(post("/ai/v1/internal/questions/recommendations")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(request)))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("embedding_unavailable"))
			.andExpect(jsonPath("$.retryable").value(true))
			.andExpect(jsonPath("$.message").doesNotExist());
	}

	@Test
	void returnsANonLeakyRetryableEnvelopeForUnexpectedFailures() throws Exception {
		InternalQuestionRecommendationRequest request = request();
		doThrow(new IllegalStateException("database host must not leak"))
			.when(recommendationService).recommend(eq(request));

		mockMvc.perform(post("/ai/v1/internal/questions/recommendations")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(request)))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("internal_question_recommendation_error"))
			.andExpect(jsonPath("$.retryable").value(true))
			.andExpect(jsonPath("$.message").doesNotExist());
	}

	private InternalQuestionRecommendationRequest request() {
		return new InternalQuestionRecommendationRequest(
			"버스 타는 법이 궁금해요",
			"한국에서는 어디로 타고 내려요?",
			location(),
			20
		);
	}

	private QuestionRecommendationLocation location() {
		return new QuestionRecommendationLocation(
			new BigDecimal("37.5"),
			new BigDecimal("127.0"),
			"서울특별시 ...",
			"",
			""
		);
	}

	private InternalQuestionRecommendationResponse response() {
		return new InternalQuestionRecommendationResponse(List.of(new InternalQuestionRecommendationItem(
			3L,
			44L,
			"한국 버스 승하차 방법",
			new BigDecimal("0.87"),
			"general",
			true,
			new RecommendedAcceptedAnswer("앞문 승차, 뒷문 하차가 일반적입니다.", false)
		)));
	}
}
