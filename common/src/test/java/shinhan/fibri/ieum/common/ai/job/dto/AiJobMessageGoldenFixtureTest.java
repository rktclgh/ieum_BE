package shinhan.fibri.ieum.common.ai.job.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.ai.job.AiJobType;

class AiJobMessageGoldenFixtureTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void questionAnswerDispatchMatchesGoldenFixture() throws Exception {
		JsonNode golden = readFixture("question-answer-dispatch-v1.json");

		QuestionAnswerDispatchMessage message = objectMapper.treeToValue(golden, QuestionAnswerDispatchMessage.class);

		assertThat(message.schemaVersion()).isEqualTo(1);
		assertThat(message.jobId()).isEqualTo("0f2a8b41-6d4e-4a2b-9f61-2c8f0d1e3a77");
		assertThat(message.jobType()).isEqualTo(AiJobType.QUESTION_ANSWER_DISPATCH);
		assertThat(message.questionId()).isEqualTo(12345L);
		assertThat(message.reason()).isEqualTo("created");
		assertThat(message.occurredAt()).isEqualTo("2026-09-02T09:15:04.512Z");

		assertReserializesToGoldenText(message, golden);
	}

	@Test
	void acceptedAnswerKnowledgeMatchesGoldenFixture() throws Exception {
		JsonNode golden = readFixture("accepted-answer-knowledge-v1.json");

		AcceptedAnswerKnowledgeMessage message =
			objectMapper.treeToValue(golden, AcceptedAnswerKnowledgeMessage.class);

		assertThat(message.schemaVersion()).isEqualTo(1);
		assertThat(message.jobId()).isEqualTo("b4c1e2a9-6d4e-4a2b-9f61-2c8f0d1e3a78");
		assertThat(message.jobType()).isEqualTo(AiJobType.ACCEPTED_ANSWER_KNOWLEDGE_INGEST);
		assertThat(message.answerId()).isEqualTo(6789L);
		assertThat(message.occurredAt()).isEqualTo("2026-09-02T09:20:31.004Z");

		assertReserializesToGoldenText(message, golden);
	}

	@Test
	void questionAnswerCompletedMatchesGoldenFixture() throws Exception {
		JsonNode golden = readFixture("question-answer-completed-v1.json");

		QuestionAnswerCompletedMessage message =
			objectMapper.treeToValue(golden, QuestionAnswerCompletedMessage.class);

		assertThat(message.schemaVersion()).isEqualTo(1);
		assertThat(message.jobId()).isEqualTo("9a77c0d3-6d4e-4a2b-9f61-2c8f0d1e3a79");
		assertThat(message.jobType()).isEqualTo(AiJobType.QUESTION_ANSWER_COMPLETED);
		assertThat(message.questionId()).isEqualTo(12345L);
		assertThat(message.answerId()).isEqualTo(98765L);
		assertThat(message.occurredAt()).isEqualTo("2026-09-02T09:16:47.883Z");

		assertReserializesToGoldenText(message, golden);
	}

	private JsonNode readFixture(String fileName) throws Exception {
		try (InputStream in = getClass().getResourceAsStream("/contract/ai-job/" + fileName)) {
			return objectMapper.readTree(in);
		}
	}

	/**
	 * {@link JsonNode#equals} 는 IntNode/LongNode 처럼 값은 같지만 서브타입이 다른 숫자 노드를
	 * 다르다고 판정한다. 계약 회귀 검증의 목적은 "같은 JSON 텍스트로 왕복되는가" 이므로
	 * 정규화된 문자열 표현으로 비교한다.
	 */
	private void assertReserializesToGoldenText(Object message, JsonNode golden) throws Exception {
		String reserializedText = objectMapper.writeValueAsString(objectMapper.valueToTree(message));
		String goldenText = objectMapper.writeValueAsString(golden);
		assertThat(reserializedText).isEqualTo(goldenText);
	}
}
