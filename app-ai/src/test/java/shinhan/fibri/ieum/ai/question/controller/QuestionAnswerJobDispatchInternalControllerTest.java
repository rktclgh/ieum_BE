package shinhan.fibri.ieum.ai.question.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerJobDispatchResult;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerJobDispatchService;

class QuestionAnswerJobDispatchInternalControllerTest {

	private final QuestionAnswerJobDispatchService service = mock(QuestionAnswerJobDispatchService.class);
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(new QuestionAnswerJobDispatchInternalController(service, 5))
			.build();
	}

	@Test
	void returnsAcceptedForNewAndDuplicateActiveDispatch() throws Exception {
		when(service.dispatch(42L)).thenReturn(QuestionAnswerJobDispatchResult.ENQUEUED);
		mockMvc.perform(post("/ai/v1/internal/question-answer-jobs/42/dispatch"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("enqueued"));

		when(service.dispatch(42L)).thenReturn(QuestionAnswerJobDispatchResult.ALREADY_ACTIVE);
		mockMvc.perform(post("/ai/v1/internal/question-answer-jobs/42/dispatch"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("already_active"));
	}

	@Test
	void returnsOkForCompletedTask() throws Exception {
		when(service.dispatch(42L)).thenReturn(QuestionAnswerJobDispatchResult.ALREADY_COMPLETED);

		mockMvc.perform(post("/ai/v1/internal/question-answer-jobs/42/dispatch"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("already_completed"));
	}

	@Test
	void returnsStableTerminalAndInvariantCodes() throws Exception {
		when(service.dispatch(42L)).thenReturn(QuestionAnswerJobDispatchResult.INVARIANT_BREACH);
		mockMvc.perform(post("/ai/v1/internal/question-answer-jobs/42/dispatch"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.status").value("question_task_invariant_breach"));

		when(service.dispatch(42L)).thenReturn(QuestionAnswerJobDispatchResult.CANCELLED_OR_DELETED);
		mockMvc.perform(post("/ai/v1/internal/question-answer-jobs/42/dispatch"))
			.andExpect(status().isGone())
			.andExpect(jsonPath("$.status").value("question_cancelled_or_deleted"));

		when(service.dispatch(42L)).thenReturn(QuestionAnswerJobDispatchResult.DEAD);
		mockMvc.perform(post("/ai/v1/internal/question-answer-jobs/42/dispatch"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.status").value("question_answer_job_dead"));
	}

	@Test
	void returnsRetryAfterForSaturatedAndDisabledLane() throws Exception {
		when(service.dispatch(42L)).thenReturn(QuestionAnswerJobDispatchResult.SATURATED);
		mockMvc.perform(post("/ai/v1/internal/question-answer-jobs/42/dispatch"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(header().string("Retry-After", "5"))
			.andExpect(jsonPath("$.status").value("question_answer_dispatch_saturated"));

		when(service.dispatch(42L)).thenReturn(QuestionAnswerJobDispatchResult.DISABLED);
		mockMvc.perform(post("/ai/v1/internal/question-answer-jobs/42/dispatch"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(header().string("Retry-After", "5"))
			.andExpect(jsonPath("$.status").value("question_answer_dispatch_disabled"));
	}
}
