package shinhan.fibri.ieum.ai.question.citation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.question.finalization.GroundedQuestionAnswerFinalization;
import shinhan.fibri.ieum.ai.question.finalization.QuestionAnswerFinalizationContext;
import shinhan.fibri.ieum.ai.question.finalization.QuestionAnswerMode;
import shinhan.fibri.ieum.ai.question.finalization.QuestionTaskFence;
import shinhan.fibri.ieum.ai.question.retrieval.GeoScope;
import shinhan.fibri.ieum.ai.question.retrieval.VectorKnowledgeEvidence;

class QuestionAnswerCitationAssemblerTest {

	private static final Instant RETRIEVED_AT = Instant.parse("2026-07-13T01:02:03Z");

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final QuestionAnswerCitationAssembler assembler = new QuestionAnswerCitationAssembler(objectMapper);

	@Test
	void assemblesOnlyCitedEvidenceInAnswerRangeOrderAndPassesFinalizationContract() {
		String answer = "앞문 승차, 뒷문 하차";
		List<VectorKnowledgeEvidence> retrieved = List.of(
			evidence(11L, 101L, "첫 번째", "https://example.org/bus", "transportation"),
			evidence(22L, 202L, "두 번째", null, null),
			evidence(33L, 303L, "인용되지 않음", "https://example.org/unused", "unused")
		);

		List<JsonNode> assembled = assembler.assemble(
			answer,
			retrieved,
			List.of(
				new AnswerCitation(1, 7, 12),
				new AnswerCitation(0, 0, 5)
			)
		);

		assertThat(assembled).hasSize(2);
		assertThat(assembled.get(0).get("sourceId").longValue()).isEqualTo(11L);
		assertThat(assembled.get(1).get("sourceId").longValue()).isEqualTo(22L);
		assertThat(assembled).noneMatch(node -> node.get("sourceId").longValue() == 33L);

		QuestionAnswerFinalizationContext context = context(assembled);
		assertThat(new GroundedQuestionAnswerFinalization(
			new QuestionTaskFence(1L, "worker-a", UUID.randomUUID()),
			QuestionAnswerMode.LOCAL_GROUNDED,
			answer,
			context
		)).isNotNull();
	}

	@Test
	void emitsTheExplicitKnowledgeChunkSchemaWithoutRetrievalInternals() {
		JsonNode node = assembler.assemble(
			"근거 문장",
			List.of(evidence(11L, 101L, "공식 안내", "https://example.org/guide", "transportation")),
			List.of(new AnswerCitation(0, 0, 2))
		).getFirst();

		assertThat(fieldNames(node)).containsExactly(
			"type",
			"sourceId",
			"chunkId",
			"sourceType",
			"title",
			"excerpt",
			"url",
			"domain",
			"contentHash",
			"score",
			"startIndex",
			"endIndex",
			"retrievedAt"
		);
		assertThat(node.get("type").textValue()).isEqualTo("knowledge_chunk");
		assertThat(node.get("url").textValue()).isEqualTo("https://example.org/guide");
		assertThat(node.get("score").decimalValue()).isEqualByComparingTo("0.82");
		assertThat(node.get("retrievedAt").textValue()).isEqualTo("2026-07-13T01:02:03Z");
		assertThat(List.of(
			"canonicalUrl",
			"sourceGrade",
			"riskDomain",
			"sourceGeoScope",
			"cosineSimilarity",
			"semanticScore",
			"geoScore",
			"distanceKm"
		)).noneMatch(node::has);
	}

	@Test
	void omitsOptionalUrlAndDomainInsteadOfSerializingNulls() {
		JsonNode node = assembler.assemble(
			"근거 문장",
			List.of(evidence(11L, 101L, "공식 안내", null, null)),
			List.of(new AnswerCitation(0, 0, 2))
		).getFirst();

		assertThat(node.has("url")).isFalse();
		assertThat(node.has("domain")).isFalse();
		assertThat(fieldNames(node)).containsExactly(
			"type",
			"sourceId",
			"chunkId",
			"sourceType",
			"title",
			"excerpt",
			"contentHash",
			"score",
			"startIndex",
			"endIndex",
			"retrievedAt"
		);
	}

	@Test
	void validatesAnswerCitationBoundsAgainstJavaStringLength() {
		String answer = "A😀B";
		List<VectorKnowledgeEvidence> retrieved = List.of(evidence(1L, 1L, "근거", null, null));

		assertThat(answer).hasSize(4);
		assertThat(assembler.assemble(
			answer,
			retrieved,
			List.of(new AnswerCitation(0, 1, 4))
		)).hasSize(1);
		assertThatThrownBy(() -> assembler.assemble(
			answer,
			retrieved,
			List.of(new AnswerCitation(0, 1, 5))
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("answer");
	}

	@Test
	void rejectsCitationBoundariesThatSplitASurrogatePair() {
		String answer = "A😀B";
		List<VectorKnowledgeEvidence> retrieved = List.of(evidence(1L, 1L, "근거", null, null));

		assertThatThrownBy(() -> assembler.assemble(
			answer,
			retrieved,
			List.of(new AnswerCitation(0, 2, 4))
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("surrogate pair");
		assertThatThrownBy(() -> assembler.assemble(
			answer,
			retrieved,
			List.of(new AnswerCitation(0, 0, 2))
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("surrogate pair");
	}

	@Test
	void rejectsBlankAnswerEmptyOrExcessiveCitationsAndOutOfRangeEvidence() {
		List<VectorKnowledgeEvidence> oneEvidence = List.of(evidence(1L, 1L, "근거", null, null));

		assertThatThrownBy(() -> assembler.assemble(
			" ",
			oneEvidence,
			List.of(new AnswerCitation(0, 0, 1))
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> assembler.assemble("answer", oneEvidence, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("1 to 8");
		assertThatThrownBy(() -> assembler.assemble(
			"answer",
			oneEvidence,
			List.of(new AnswerCitation(1, 0, 1))
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("evidenceIndex");

		List<VectorKnowledgeEvidence> nineEvidence = new ArrayList<>();
		List<AnswerCitation> nineCitations = new ArrayList<>();
		for (int index = 0; index < 9; index++) {
			nineEvidence.add(evidence(index + 1L, index + 1L, "근거 " + index, null, null));
			nineCitations.add(new AnswerCitation(index, index, index + 1));
		}
		assertThatThrownBy(() -> assembler.assemble("123456789", nineEvidence, nineCitations))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("1 to 8");
	}

	@Test
	void allowsOneEvidenceToSupportDistinctClaimsButRejectsConflictingExactRanges() {
		List<VectorKnowledgeEvidence> retrieved = List.of(
			evidence(1L, 1L, "첫째", null, null),
			evidence(2L, 2L, "둘째", null, null)
		);

		assertThat(assembler.assemble(
			"answer",
			retrieved,
			List.of(
				new AnswerCitation(0, 0, 1),
				new AnswerCitation(0, 2, 3)
			)
		)).hasSize(2);
		assertThatThrownBy(() -> assembler.assemble(
			"answer",
			retrieved,
			List.of(
				new AnswerCitation(0, 0, 2),
				new AnswerCitation(1, 0, 2)
			)
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("citation range");
	}

	@Test
	void rejectsNullDependenciesInputsAndListEntries() {
		List<VectorKnowledgeEvidence> retrieved = List.of(evidence(1L, 1L, "근거", null, null));
		List<VectorKnowledgeEvidence> evidenceWithNull = new ArrayList<>(retrieved);
		evidenceWithNull.add(null);
		List<AnswerCitation> citationWithNull = new ArrayList<>();
		citationWithNull.add(null);

		assertThatThrownBy(() -> new QuestionAnswerCitationAssembler(null))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> assembler.assemble(null, retrieved, List.of(new AnswerCitation(0, 0, 1))))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> assembler.assemble("answer", null, List.of(new AnswerCitation(0, 0, 1))))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> assembler.assemble("answer", retrieved, null))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> assembler.assemble("answer", evidenceWithNull, List.of(new AnswerCitation(0, 0, 1))))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> assembler.assemble("answer", retrieved, citationWithNull))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void answerCitationRejectsNegativeOrEmptyRangesBeforeAssembly() {
		assertThatThrownBy(() -> new AnswerCitation(-1, 0, 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AnswerCitation(0, -1, 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AnswerCitation(0, 1, 1))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private QuestionAnswerFinalizationContext context(List<JsonNode> evidence) {
		return new QuestionAnswerFinalizationContext(
			new ArrayList<>(Collections.nCopies(768, 0.0f)),
			"gemini-embedding-2",
			GeoScope.general,
			BigDecimal.ONE,
			objectMapper.createObjectNode(),
			"amazon-bedrock",
			"amazon.nova-micro-v1:0",
			"hybrid-rag-v1",
			null,
			"question-answer-v1",
			new BigDecimal("0.82"),
			evidence
		);
	}

	private List<String> fieldNames(JsonNode node) {
		List<String> fields = new ArrayList<>();
		node.fieldNames().forEachRemaining(fields::add);
		return fields;
	}

	private VectorKnowledgeEvidence evidence(
		long sourceId,
		long chunkId,
		String title,
		String canonicalUrl,
		String domain
	) {
		return new VectorKnowledgeEvidence(
			sourceId,
			chunkId,
			"curated",
			title,
			"버스는 앞문으로 승차하고 뒷문으로 하차합니다.",
			"public_agency",
			"a".repeat(64),
			canonicalUrl,
			"transportation",
			domain,
			GeoScope.general,
			new BigDecimal("0.91"),
			new BigDecimal("0.87"),
			new BigDecimal("0.75"),
			new BigDecimal("0.82"),
			null,
			RETRIEVED_AT
		);
	}
}
