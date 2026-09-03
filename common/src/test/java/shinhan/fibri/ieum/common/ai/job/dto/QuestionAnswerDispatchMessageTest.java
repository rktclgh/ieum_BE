package shinhan.fibri.ieum.common.ai.job.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.ai.job.AiJobType;

class QuestionAnswerDispatchMessageTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void roundTripsThroughJackson() throws Exception {
		QuestionAnswerDispatchMessage message = new QuestionAnswerDispatchMessage(
			1, "0f2a8b41-6d4e-4a2b-9f61-2c8f0d1e3a77", AiJobType.QUESTION_ANSWER_DISPATCH,
			12345L, "created", "2026-09-02T09:15:04.512Z"
		);

		String json = objectMapper.writeValueAsString(message);
		QuestionAnswerDispatchMessage roundTripped = objectMapper.readValue(json, QuestionAnswerDispatchMessage.class);

		assertThat(roundTripped).isEqualTo(message);
	}

	@Test
	void rejectsNonPositiveQuestionId() {
		assertThatThrownBy(() -> new QuestionAnswerDispatchMessage(
			1, "job-1", AiJobType.QUESTION_ANSWER_DISPATCH, 0L, "created", "2026-09-02T09:15:04.512Z"
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsUnknownReason() {
		assertThatThrownBy(() -> new QuestionAnswerDispatchMessage(
			1, "job-1", AiJobType.QUESTION_ANSWER_DISPATCH, 12345L, "unknown", "2026-09-02T09:15:04.512Z"
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void ignoresUnknownFieldsWhenDeserializing() throws Exception {
		String json = """
			{
			  "schemaVersion": 1,
			  "jobId": "job-1",
			  "jobType": "question_answer_dispatch",
			  "questionId": 12345,
			  "reason": "created",
			  "occurredAt": "2026-09-02T09:15:04.512Z",
			  "someFutureField": "ignored"
			}
			""";

		QuestionAnswerDispatchMessage message = objectMapper.readValue(json, QuestionAnswerDispatchMessage.class);

		assertThat(message.questionId()).isEqualTo(12345L);
	}
}
