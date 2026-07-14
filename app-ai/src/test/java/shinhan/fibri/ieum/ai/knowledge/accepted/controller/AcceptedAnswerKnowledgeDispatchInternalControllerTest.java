package shinhan.fibri.ieum.ai.knowledge.accepted.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import shinhan.fibri.ieum.ai.knowledge.accepted.service.AcceptedAnswerKnowledgeTaskLane;
import shinhan.fibri.ieum.ai.knowledge.accepted.service.AcceptedAnswerKnowledgeTaskSubmission;

class AcceptedAnswerKnowledgeDispatchInternalControllerTest {

	private final AcceptedAnswerKnowledgeTaskLane lane = mock(AcceptedAnswerKnowledgeTaskLane.class);
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(new AcceptedAnswerKnowledgeDispatchInternalController(lane, 5))
			.build();
	}

	@Test
	void onlyEnqueuesAndReturnsAcceptedForNewAndDuplicateActiveDispatch() throws Exception {
		when(lane.submit(42L)).thenReturn(AcceptedAnswerKnowledgeTaskSubmission.ENQUEUED);

		mockMvc.perform(post("/ai/v1/internal/accepted-answer-jobs/42/dispatch"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("enqueued"));
		verify(lane).submit(42L);

		when(lane.submit(43L)).thenReturn(AcceptedAnswerKnowledgeTaskSubmission.ALREADY_ACTIVE);
		mockMvc.perform(post("/ai/v1/internal/accepted-answer-jobs/43/dispatch"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("already_active"));
		verify(lane).submit(43L);
	}

	@Test
	void returnsRetryAfterForDisabledAndSaturatedLane() throws Exception {
		when(lane.submit(42L)).thenReturn(AcceptedAnswerKnowledgeTaskSubmission.DISABLED);
		mockMvc.perform(post("/ai/v1/internal/accepted-answer-jobs/42/dispatch"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(header().string("Retry-After", "5"))
			.andExpect(jsonPath("$.status").value("accepted_answer_ingestion_disabled"));

		when(lane.submit(43L)).thenReturn(AcceptedAnswerKnowledgeTaskSubmission.SATURATED);
		mockMvc.perform(post("/ai/v1/internal/accepted-answer-jobs/43/dispatch"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(header().string("Retry-After", "5"))
			.andExpect(jsonPath("$.status").value("accepted_answer_ingestion_saturated"));
	}

	@Test
	void rejectsNonPositiveIdBeforeTouchingTheLane() throws Exception {
		mockMvc.perform(post("/ai/v1/internal/accepted-answer-jobs/0/dispatch"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value("invalid_answer_id"));

		verifyNoInteractions(lane);
	}
}
