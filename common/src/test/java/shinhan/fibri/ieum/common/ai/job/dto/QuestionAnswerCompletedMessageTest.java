package shinhan.fibri.ieum.common.ai.job.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.ai.job.AiJobType;

class QuestionAnswerCompletedMessageTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void roundTripsThroughJackson() throws Exception {
		QuestionAnswerCompletedMessage message = new QuestionAnswerCompletedMessage(
			1, "9a77c0d3-6d4e-4a2b-9f61-2c8f0d1e3a79", AiJobType.QUESTION_ANSWER_COMPLETED,
			12345L, 98765L, "2026-09-02T09:16:47.883Z"
		);

		String json = objectMapper.writeValueAsString(message);
		QuestionAnswerCompletedMessage roundTripped =
			objectMapper.readValue(json, QuestionAnswerCompletedMessage.class);

		assertThat(roundTripped).isEqualTo(message);
	}

	@Test
	void rejectsNonPositiveQuestionId() {
		assertThatThrownBy(() -> new QuestionAnswerCompletedMessage(
			1, "job-1", AiJobType.QUESTION_ANSWER_COMPLETED, 0L, 98765L, "2026-09-02T09:16:47.883Z"
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNonPositiveAnswerId() {
		assertThatThrownBy(() -> new QuestionAnswerCompletedMessage(
			1, "job-1", AiJobType.QUESTION_ANSWER_COMPLETED, 12345L, 0L, "2026-09-02T09:16:47.883Z"
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void ignoresUnknownFieldsWhenDeserializing() throws Exception {
		String json = """
			{
			  "schemaVersion": 1,
			  "jobId": "job-1",
			  "jobType": "question_answer_completed",
			  "questionId": 12345,
			  "answerId": 98765,
			  "occurredAt": "2026-09-02T09:16:47.883Z",
			  "someFutureField": "ignored"
			}
			""";

		QuestionAnswerCompletedMessage message =
			objectMapper.readValue(json, QuestionAnswerCompletedMessage.class);

		assertThat(message.questionId()).isEqualTo(12345L);
		assertThat(message.answerId()).isEqualTo(98765L);
	}
}
