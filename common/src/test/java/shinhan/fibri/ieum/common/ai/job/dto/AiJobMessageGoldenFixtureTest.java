package shinhan.fibri.ieum.common.ai.job.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Map;
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
	 * 다르다고 판정하고, 원시 JSON 텍스트 비교는 record 컴포넌트 순서에 우연히 의존하게 된다
	 * (와이어 계약과 무관한 필드 순서 변경에도 깨짐).
	 *
	 * <p>양쪽을 각자의 JSON 텍스트로 직렬화한 뒤 {@code readValue(..., Map.class)} 로 다시 파싱해
	 * 비교한다. Jackson 이 Object/Map 역직렬화 시 숫자를 "값이 int 범위에 들어오면 Integer, 아니면
	 * Long" 규칙으로 정규화하는 지점은 이 파싱 단계 하나뿐이므로, 원래 자바 타입이 int/long 무엇이든
	 * (그리고 소스가 파일이든 POJO 직렬화 결과든) 양쪽이 동일한 규칙을 거쳐 같은 {@link Map} 표현이
	 * 된다 — 키 순서와 무관하되 필드명·값은 엄격하게 비교된다.
	 */
	private void assertReserializesToGoldenText(Object message, JsonNode golden) throws Exception {
		Map<?, ?> reserialized = objectMapper.readValue(objectMapper.writeValueAsString(message), Map.class);
		Map<?, ?> goldenMap = objectMapper.readValue(objectMapper.writeValueAsString(golden), Map.class);
		assertThat(reserialized).isEqualTo(goldenMap);
	}
}
