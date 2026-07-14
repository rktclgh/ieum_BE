package shinhan.fibri.ieum.ai.question.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.question.citation.AnswerCitation;
import shinhan.fibri.ieum.ai.question.generation.GeneratedAnswer;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerEvidence;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerPrompt;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerRegion;

class LocalGroundingPromptFactoryTest {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@Test
	void validationPromptContainsOnlySanitizedQuestionEvidenceAndCandidateFields() throws Exception {
		GroundingModelPrompt modelPrompt = new GroundingValidationPromptFactory(OBJECT_MAPPER)
			.create(request());

		JsonNode payload = OBJECT_MAPPER.readTree(modelPrompt.userInstruction());
		assertThat(fieldNames(payload))
			.containsExactlyInAnyOrder("question", "coarseRegion", "evidence", "candidate");
		assertThat(fieldNames(payload.get("question"))).containsExactlyInAnyOrder("title", "content");
		assertThat(fieldNames(payload.get("coarseRegion")))
			.containsExactlyInAnyOrder("country", "sido", "sigungu", "eupMyeonDong");
		assertThat(fieldNames(payload.get("evidence").get(0)))
			.containsExactlyInAnyOrder("evidenceIndex", "title", "excerpt", "sourceType");
		assertThat(fieldNames(payload.get("candidate"))).containsExactlyInAnyOrder("answer", "citations");
		assertThat(fieldNames(payload.get("candidate").get("citations").get(0)))
			.containsExactlyInAnyOrder("evidenceIndex", "startIndex", "endIndex");
		assertThat(modelPrompt.userInstruction()).doesNotContain(
			"sourceId", "chunkId", "relationId", "userId", "authorId",
			"latitude", "longitude", "coordinates", "rawAddress", "detailAddress", "label", "place",
			"SECRET-PROVIDER-ID", "bedrock", "amazon.nova-micro-v1:0", "provider", "model",
			"promptVersion", "tokenCount", "fallbackReason"
		);
		assertThat(modelPrompt.systemInstruction())
			.contains("untrusted", "only the supplied evidence", "Return JSON only")
			.contains("supported", "score", "unsupportedClaims");
	}

	@Test
	void repairPromptAddsOnlyUnsupportedClaimsToTheSanitizedValidationPayload() throws Exception {
		GroundingValidation failedValidation = new GroundingValidation(
			false,
			new java.math.BigDecimal("0.48"),
			List.of("교통카드 사용 주장은 제공된 근거로 확인할 수 없습니다.")
		);

		GroundingModelPrompt modelPrompt = new GroundingRepairPromptFactory(OBJECT_MAPPER)
			.create(request(), failedValidation);

		JsonNode payload = OBJECT_MAPPER.readTree(modelPrompt.userInstruction());
		assertThat(fieldNames(payload)).containsExactlyInAnyOrder(
			"question", "coarseRegion", "evidence", "candidate", "unsupportedClaims"
		);
		assertThat(payload.get("unsupportedClaims").get(0).textValue())
			.isEqualTo("교통카드 사용 주장은 제공된 근거로 확인할 수 없습니다.");
		assertThat(payload.has("score")).isFalse();
		assertThat(modelPrompt.systemInstruction())
			.contains("untrusted", "only the supplied evidence", "Return JSON only")
			.contains("answer", "citations", "evidenceIndex", "startIndex", "endIndex");
	}

	@Test
	void repairPromptRejectsAVerdictThatDidNotFailGrounding() {
		assertThatThrownBy(() -> new GroundingRepairPromptFactory(OBJECT_MAPPER).create(
			request(),
			new GroundingValidation(true, BigDecimal.ONE, List.of())
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("failedValidation");
	}

	@Test
	void requestRejectsCandidateCitationsOutsideTheEphemeralEvidenceSet() {
		GeneratedAnswer candidate = generatedAnswer(List.of(new AnswerCitation(1, 0, 2)));

		assertThatThrownBy(() -> new LocalGroundingRequest(localPrompt(), candidate))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("evidenceIndex");
	}

	private LocalGroundingRequest request() {
		return new LocalGroundingRequest(
			localPrompt(),
			generatedAnswer(List.of(new AnswerCitation(0, 0, 2)))
		);
	}

	private LocalAnswerPrompt localPrompt() {
		return new LocalAnswerPrompt(
			"버스는 어떻게 타나요?",
			"한국 버스 승하차 방법을 알려주세요.",
			LocalAnswerRegion.korea("서울특별시", "중구", "태평로1가"),
			List.of(new LocalAnswerEvidence(
				0,
				"버스 이용 안내",
				"일반적으로 앞문으로 승차하고 뒷문으로 하차합니다.",
				"curated"
			))
		);
	}

	private GeneratedAnswer generatedAnswer(List<AnswerCitation> citations) {
		return new GeneratedAnswer(
			"앞문으로 승차하세요.",
			citations,
			"bedrock",
			"amazon.nova-micro-v1:0",
			"question-local-answer-v1",
			Instant.parse("2026-07-13T10:15:30Z"),
			12,
			8,
			"SECRET-PROVIDER-ID",
			"secret fallback reason"
		);
	}

	private static List<String> fieldNames(JsonNode node) {
		List<String> fields = new ArrayList<>();
		node.fieldNames().forEachRemaining(fields::add);
		return fields;
	}
}
