package shinhan.fibri.ieum.ai.question.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.question.retrieval.GeoScope;

class QuestionAnswerFinalizationInputTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void contextIsImmutableAndAcceptsOnlyTheFixedEmbeddingContract() {
		List<Float> embedding = embedding();
		JsonNode region = objectMapper.createObjectNode().put("sido", "서울특별시");
		JsonNode evidence = evidence();
		QuestionAnswerFinalizationContext context = context(
			embedding,
			"gemini-embedding-2",
			GeoScope.regional,
			new BigDecimal("0.75"),
			region,
			"amazon-bedrock",
			new BigDecimal("0.90"),
			List.of(evidence)
		);

		embedding.set(0, 0.25f);
		((com.fasterxml.jackson.databind.node.ObjectNode) region).put("sido", "변경");
		((com.fasterxml.jackson.databind.node.ObjectNode) evidence).put("type", "변경");
		((com.fasterxml.jackson.databind.node.ObjectNode) context.regionContext()).put("sido", "노출 변경");
		((com.fasterxml.jackson.databind.node.ObjectNode) context.evidence().getFirst()).put("type", "노출 변경");

		assertThat(context.embedding().getFirst()).isEqualTo(1.0f);
		assertThat(context.regionContext().get("sido").textValue()).isEqualTo("서울특별시");
		assertThat(context.evidence().getFirst().get("type").textValue()).isEqualTo("knowledge_chunk");
	}

	@Test
	void rejectsInvalidEmbeddingDimensionValueOrModel() {
		assertThatThrownBy(() -> context(
			embedding().subList(0, 767),
			"gemini-embedding-2",
			GeoScope.general,
			BigDecimal.ONE,
			objectMapper.createObjectNode(),
			"amazon-bedrock",
			BigDecimal.ONE,
			List.of(evidence())
		)).isInstanceOf(IllegalArgumentException.class);

		List<Float> nonFinite = embedding();
		nonFinite.set(4, Float.NaN);
		assertThatThrownBy(() -> context(
			nonFinite,
			"gemini-embedding-2",
			GeoScope.general,
			BigDecimal.ONE,
			objectMapper.createObjectNode(),
			"amazon-bedrock",
			BigDecimal.ONE,
			List.of(evidence())
		)).isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> context(
			embedding(),
			"gemini-embedding-001",
			GeoScope.general,
			BigDecimal.ONE,
			objectMapper.createObjectNode(),
			"amazon-bedrock",
			BigDecimal.ONE,
			List.of(evidence())
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsInvalidGeoJsonProbabilityOrProvenance() {
		assertThatThrownBy(() -> context(
			embedding(),
			"gemini-embedding-2",
			null,
			BigDecimal.ONE,
			objectMapper.createObjectNode(),
			"amazon-bedrock",
			BigDecimal.ONE,
			List.of(evidence())
		)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> context(
			embedding(),
			"gemini-embedding-2",
			GeoScope.general,
			new BigDecimal("1.01"),
			objectMapper.createObjectNode(),
			"amazon-bedrock",
			BigDecimal.ONE,
			List.of(evidence())
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> context(
			embedding(),
			"gemini-embedding-2",
			GeoScope.general,
			BigDecimal.ONE,
			objectMapper.createArrayNode(),
			"amazon-bedrock",
			BigDecimal.ONE,
			List.of(evidence())
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> context(
			embedding(),
			"gemini-embedding-2",
			GeoScope.general,
			BigDecimal.ONE,
			objectMapper.createObjectNode(),
			" ",
			BigDecimal.ONE,
			List.of(evidence())
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> context(
			embedding(),
			"gemini-embedding-2",
			GeoScope.general,
			BigDecimal.ONE,
			objectMapper.createObjectNode(),
			"amazon-bedrock",
			new BigDecimal("-0.01"),
			List.of(evidence())
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void groundedRequiresContentAndOneToEightEvidenceObjects() {
		QuestionTaskFence fence = fence();
		assertThatThrownBy(() -> new GroundedQuestionAnswerFinalization(
			fence,
			QuestionAnswerMode.LOCAL_GROUNDED,
			" ",
			context(List.of(evidence()))
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GroundedQuestionAnswerFinalization(
			fence,
			QuestionAnswerMode.LOCAL_GROUNDED,
			"answer",
			context(List.of())
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GroundedQuestionAnswerFinalization(
			fence,
			QuestionAnswerMode.WEB_GROUNDED,
			"answer",
			context(java.util.Collections.nCopies(9, evidence()))
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> context(List.of(objectMapper.createArrayNode())))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void evidenceRequiresCanonicalCommonAndTypeSpecificFields() {
		assertThatThrownBy(() -> context(List.of(objectMapper.createObjectNode())))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> context(List.of(evidence().deepCopy().put("type", "unknown"))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> context(List.of(evidence().deepCopy().put("providerRaw", "forbidden"))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> context(List.of(withoutField(evidence(), "contentHash"))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> context(List.of(evidence().deepCopy().put("score", 1.01d))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> context(List.of(evidence().deepCopy().put("retrievedAt", "not-an-instant"))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> context(List.of(withoutField(evidence(), "sourceId"))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> context(List.of(withoutField(evidence(), "chunkId"))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> context(List.of(withoutField(kgEvidence(), "relationId"))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> context(List.of(withoutField(kgEvidence(), "chunkId"))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> context(List.of(withoutField(
			webEvidence("https://example.org/guide", 0, 3),
			"url"
		))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> context(List.of(withoutField(
			webEvidence("https://example.org/guide", 0, 3),
			"domain"
		))))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void answerModeRestrictsEvidenceKindAndRequiresWebCitation() {
		ObjectNode uncitedLocalEvidence = evidence();
		uncitedLocalEvidence.putNull("startIndex");
		uncitedLocalEvidence.putNull("endIndex");
		assertThatThrownBy(() -> new GroundedQuestionAnswerFinalization(
			fence(),
			QuestionAnswerMode.LOCAL_GROUNDED,
			"answer",
			context(List.of(uncitedLocalEvidence))
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GroundedQuestionAnswerFinalization(
			fence(),
			QuestionAnswerMode.LOCAL_GROUNDED,
			"answer",
			context(List.of(webEvidence("https://example.org/guide", 0, 3)))
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GroundedQuestionAnswerFinalization(
			fence(),
			QuestionAnswerMode.WEB_GROUNDED,
			"answer",
			context(List.of(evidence()))
		)).isInstanceOf(IllegalArgumentException.class);

		assertThat(new GroundedQuestionAnswerFinalization(
			fence(),
			QuestionAnswerMode.LOCAL_GROUNDED,
			"answer",
			context(List.of(evidence(), kgEvidence()))
		)).isNotNull();
		assertThat(new GroundedQuestionAnswerFinalization(
			fence(),
			QuestionAnswerMode.WEB_GROUNDED,
			"answer",
			context(List.of(webEvidence("https://example.org/guide", 0, 3)))
		)).isNotNull();
	}

	@Test
	void webEvidenceRejectsUnsafeUrlAndOutOfRangeAnnotation() {
		assertThatThrownBy(() -> context(List.of(webEvidence("ftp://example.org/guide", 0, 3))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> context(List.of(webEvidence("https://user@example.org/guide", 0, 3))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> context(List.of(evidence().put("url", "https://user@example.org/guide"))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GroundedQuestionAnswerFinalization(
			fence(),
			QuestionAnswerMode.WEB_GROUNDED,
			"answer",
			context(List.of(webEvidence("https://example.org/guide", 0, 7)))
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GroundedQuestionAnswerFinalization(
			fence(),
			QuestionAnswerMode.WEB_GROUNDED,
			"answer",
			context(List.of(webEvidence("https://example.org/guide", 3, 3)))
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void citationRangesAreUniqueAndCannotSplitUtf16SurrogatePairsButMayOverlap() {
		String content = "A😀BCDE";
		ObjectNode first = evidence().put("startIndex", 0).put("endIndex", 4);
		ObjectNode overlapping = kgEvidence().put("startIndex", 3).put("endIndex", 6);

		assertThat(new GroundedQuestionAnswerFinalization(
			fence(),
			QuestionAnswerMode.LOCAL_GROUNDED,
			content,
			context(List.of(first, overlapping))
		)).isNotNull();

		ObjectNode duplicateRange = kgEvidence().put("startIndex", 0).put("endIndex", 4);
		assertThatThrownBy(() -> new GroundedQuestionAnswerFinalization(
			fence(),
			QuestionAnswerMode.LOCAL_GROUNDED,
			content,
			context(List.of(first, duplicateRange))
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("citation range");

		ObjectNode splitSurrogate = evidence().put("startIndex", 2).put("endIndex", 4);
		assertThatThrownBy(() -> new GroundedQuestionAnswerFinalization(
			fence(),
			QuestionAnswerMode.LOCAL_GROUNDED,
			content,
			context(List.of(splitSurrogate))
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("surrogate pair");
	}

	@Test
	void preservesNonblankAnswerContentExactlySoCitationOffsetsRemainStable() {
		String content = " answer ";
		GroundedQuestionAnswerFinalization command = new GroundedQuestionAnswerFinalization(
			fence(),
			QuestionAnswerMode.LOCAL_GROUNDED,
			content,
			context(List.of(evidence()))
		);

		assertThat(command.content()).isSameAs(content).isEqualTo(" answer ");
	}

	@Test
	void insufficientRequiresAnEmptyEvidenceSnapshot() {
		assertThatThrownBy(() -> new InsufficientQuestionAnswerFinalization(
			fence(),
			context(List.of(evidence()))
		)).isInstanceOf(IllegalArgumentException.class);
		assertThat(new InsufficientQuestionAnswerFinalization(fence(), context(List.of())))
			.isNotNull();
	}

	@Test
	void insufficientMayOmitGenerationProvenanceButGroundedMayNot() {
		QuestionAnswerFinalizationContext withoutGeneration = new QuestionAnswerFinalizationContext(
			embedding(),
			"gemini-embedding-2",
			GeoScope.general,
			BigDecimal.ONE,
			objectMapper.createObjectNode(),
			null,
			null,
			"hybrid-rag-v1",
			"no_local_evidence",
			null,
			BigDecimal.ZERO,
			List.of()
		);

		assertThat(new InsufficientQuestionAnswerFinalization(fence(), withoutGeneration)).isNotNull();
		assertThatThrownBy(() -> new GroundedQuestionAnswerFinalization(
			fence(),
			QuestionAnswerMode.LOCAL_GROUNDED,
			"answer",
			new QuestionAnswerFinalizationContext(
				embedding(),
				"gemini-embedding-2",
				GeoScope.general,
				BigDecimal.ONE,
				objectMapper.createObjectNode(),
				null,
				null,
				"hybrid-rag-v1",
				null,
				null,
				BigDecimal.ONE,
				List.of(evidence())
			)
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("generation");
	}

	private QuestionTaskFence fence() {
		return new QuestionTaskFence(1L, "worker-a", UUID.randomUUID());
	}

	private QuestionAnswerFinalizationContext context(List<JsonNode> evidence) {
		return context(
			embedding(),
			"gemini-embedding-2",
			GeoScope.general,
			BigDecimal.ONE,
			objectMapper.createObjectNode(),
			"amazon-bedrock",
			BigDecimal.ONE,
			evidence
		);
	}

	private QuestionAnswerFinalizationContext context(
		List<Float> embedding,
		String embeddingModel,
		GeoScope geoScope,
		BigDecimal geoConfidence,
		JsonNode regionContext,
		String provider,
		BigDecimal groundingScore,
		List<JsonNode> evidence
	) {
		return new QuestionAnswerFinalizationContext(
			embedding,
			embeddingModel,
			geoScope,
			geoConfidence,
			regionContext,
			provider,
			"amazon.nova-micro-v1:0",
			"hybrid-rag-v1",
			null,
			"question-answer-v1",
			groundingScore,
			evidence
		);
	}

	private ObjectNode evidence() {
		return objectMapper.createObjectNode()
			.put("type", "knowledge_chunk")
			.put("sourceId", 10L)
			.put("chunkId", 20L)
			.put("sourceType", "curated")
			.put("title", "한국 버스 이용 안내")
			.put("excerpt", "버스는 앞문으로 승차합니다.")
			.put("contentHash", "a".repeat(64))
			.put("score", 0.91d)
			.put("startIndex", 0)
			.put("endIndex", 3)
			.put("retrievedAt", "2026-07-13T00:00:00Z");
	}

	private ObjectNode kgEvidence() {
		return objectMapper.createObjectNode()
			.put("type", "kg_relation")
			.put("sourceId", 10L)
			.put("chunkId", 20L)
			.put("relationId", 30L)
			.put("sourceType", "curated")
			.put("title", "한국 버스 승하차 관계")
			.put("excerpt", "버스 승차는 앞문을 사용합니다.")
			.put("contentHash", "b".repeat(64))
			.put("score", 0.87d)
			.put("retrievedAt", "2026-07-13T00:00:00Z");
	}

	private ObjectNode webEvidence(String url, int startIndex, int endIndex) {
		return objectMapper.createObjectNode()
			.put("type", "web")
			.put("title", "공식 교통 안내")
			.put("excerpt", "버스 이용 방법")
			.put("url", url)
			.put("domain", "example.org")
			.put("contentHash", "c".repeat(64))
			.put("score", 0.84d)
			.put("startIndex", startIndex)
			.put("endIndex", endIndex)
			.put("retrievedAt", "2026-07-13T00:00:00Z");
	}

	private ObjectNode withoutField(ObjectNode evidence, String field) {
		evidence.remove(field);
		return evidence;
	}

	private List<Float> embedding() {
		List<Float> values = new ArrayList<>(768);
		for (int index = 0; index < 768; index++) {
			values.add(index == 0 ? 1.0f : 0.0f);
		}
		return values;
	}
}
