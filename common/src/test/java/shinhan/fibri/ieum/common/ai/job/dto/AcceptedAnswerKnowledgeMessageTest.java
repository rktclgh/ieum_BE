package shinhan.fibri.ieum.common.ai.job.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.ai.job.AiJobType;

class AcceptedAnswerKnowledgeMessageTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void roundTripsThroughJackson() throws Exception {
		AcceptedAnswerKnowledgeMessage message = new AcceptedAnswerKnowledgeMessage(
			1, "b4c1e2a9-6d4e-4a2b-9f61-2c8f0d1e3a78", AiJobType.ACCEPTED_ANSWER_KNOWLEDGE_INGEST,
			6789L, "2026-09-02T09:20:31.004Z"
		);

		String json = objectMapper.writeValueAsString(message);
		AcceptedAnswerKnowledgeMessage roundTripped =
			objectMapper.readValue(json, AcceptedAnswerKnowledgeMessage.class);

		assertThat(roundTripped).isEqualTo(message);
	}

	@Test
	void rejectsNonPositiveAnswerId() {
		assertThatThrownBy(() -> new AcceptedAnswerKnowledgeMessage(
			1, "job-1", AiJobType.ACCEPTED_ANSWER_KNOWLEDGE_INGEST, 0L, "2026-09-02T09:20:31.004Z"
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void ignoresUnknownFieldsWhenDeserializing() throws Exception {
		String json = """
			{
			  "schemaVersion": 1,
			  "jobId": "job-1",
			  "jobType": "accepted_answer_knowledge_ingest",
			  "answerId": 6789,
			  "occurredAt": "2026-09-02T09:20:31.004Z",
			  "someFutureField": "ignored"
			}
			""";

		AcceptedAnswerKnowledgeMessage message = objectMapper.readValue(json, AcceptedAnswerKnowledgeMessage.class);

		assertThat(message.answerId()).isEqualTo(6789L);
	}
}
