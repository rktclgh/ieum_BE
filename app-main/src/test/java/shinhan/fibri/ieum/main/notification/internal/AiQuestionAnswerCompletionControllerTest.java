package shinhan.fibri.ieum.main.notification.internal;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;

@WebMvcTest(AiQuestionAnswerCompletionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
	AiQuestionAnswerCompletionExceptionHandler.class,
	InternalAiCallbackTokenVerifier.class
})
@TestPropertySource(properties = "app.ai.internal-callback-token=test-internal-token")
class AiQuestionAnswerCompletionControllerTest {

	private static final String TOKEN = "test-internal-token";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AiQuestionAnswerCompletionService service;

	@MockitoBean
	private SessionTokenValidator sessionTokenValidator;

	@Test
	void validInternalTokenCompletesTheTicket() throws Exception {
		mockMvc.perform(post("/api/v1/internal/ai/question-answer-jobs/{questionId}/completed", 10L)
				.header("X-IEUM-Internal-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"answerId\":40}"))
			.andExpect(status().isNoContent());

		verify(service).complete(10L, 40L);
	}

	@Test
	void missingOrInvalidInternalTokenIsUnauthorized() throws Exception {
		mockMvc.perform(post("/api/v1/internal/ai/question-answer-jobs/{questionId}/completed", 10L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"answerId\":40}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("INVALID_INTERNAL_AI_TOKEN")));

		mockMvc.perform(post("/api/v1/internal/ai/question-answer-jobs/{questionId}/completed", 10L)
				.header("X-IEUM-Internal-Token", "wrong-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"answerId\":40}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("INVALID_INTERNAL_AI_TOKEN")));
	}

	@Test
	void missingTicketIsNotFoundWithSafeEnvelope() throws Exception {
		org.mockito.Mockito.doThrow(new AiQuestionAnswerTicketNotFoundException())
			.when(service).complete(10L, 40L);

		mockMvc.perform(post("/api/v1/internal/ai/question-answer-jobs/{questionId}/completed", 10L)
				.header("X-IEUM-Internal-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"answerId\":40}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("AI_ANSWER_JOB_NOT_FOUND")));
	}

	@Test
	void incompleteOrMismatchedTicketIsConflictWithSafeEnvelope() throws Exception {
		org.mockito.Mockito.doThrow(new AiQuestionAnswerCompletionConflictException())
			.when(service).complete(10L, 40L);

		mockMvc.perform(post("/api/v1/internal/ai/question-answer-jobs/{questionId}/completed", 10L)
				.header("X-IEUM-Internal-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"answerId\":40}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("AI_ANSWER_JOB_CONFLICT")));
	}

	@Test
	void missingAnswerIdIsBadRequestWithSafeEnvelope() throws Exception {
		mockMvc.perform(post("/api/v1/internal/ai/question-answer-jobs/{questionId}/completed", 10L)
				.header("X-IEUM-Internal-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("AI_ANSWER_JOB_INVALID_REQUEST")));
	}

	@Test
	void nonNumericAnswerIdIsBadRequestWithSafeEnvelope() throws Exception {
		mockMvc.perform(post("/api/v1/internal/ai/question-answer-jobs/{questionId}/completed", 10L)
				.header("X-IEUM-Internal-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"answerId\":\"not-a-number\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("AI_ANSWER_JOB_INVALID_REQUEST")));
	}

}
