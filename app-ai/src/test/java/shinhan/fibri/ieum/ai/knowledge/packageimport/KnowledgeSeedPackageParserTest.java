package shinhan.fibri.ieum.ai.knowledge.packageimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeSeedPackageParserTest {

	private static final String RESOURCE = "/knowledge/korea_long_stay_seed_v0.2.json";
	private static final String EXPECTED_MANIFEST_HASH =
		"1df465ec6a31d2d41c52681a7ee6b0c56960062c80af004034920621d784b39b";

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final KnowledgeSeedPackageParser parser = new KnowledgeSeedPackageParser(objectMapper);

	@Test
	void parsesCanonicalPackageAndPreservesItsTypedFields() throws IOException {
		KnowledgeSeedPackage seedPackage = parseResource();

		assertThat(seedPackage.schemaVersion()).isEqualTo("1.0");
		assertThat(seedPackage.packageKey()).isEqualTo("korea-long-stay-seed");
		assertThat(seedPackage.packageVersion()).isEqualTo("2026-07-13.2");
		assertThat(seedPackage.canonicalDraft()).isEqualTo("../korea_tacit_knowledge_graph.json");
		assertThat(seedPackage.documentType()).isEqualTo("seed_knowledge_pack");
		assertThat(seedPackage.answerEligible()).isTrue();
		assertThat(seedPackage.language()).isEqualTo("ko");
		assertThat(seedPackage.embeddingPolicy()).isEqualTo("one_source_one_chunk");
		assertThat(seedPackage.manifestHash()).isEqualTo(EXPECTED_MANIFEST_HASH);
		assertThat(seedPackage.expectedSourceCount()).isEqualTo(20);
		assertThat(seedPackage.predicateVocabulary()).hasSize(10);
		assertThat(seedPackage.reviewView()).isEqualTo("korea_long_stay_seed_v0.2.md");
		assertThat(seedPackage.sources()).hasSize(20);
		assertThat(seedPackage.sources()).flatExtracting(KnowledgeSeedPackage.Source::relations).hasSize(50);

		KnowledgeSeedPackage.Source first = seedPackage.sources().getFirst();
		assertThat(first.sourceKey()).isEqualTo("kr-foreigner-registration-90-days");
		assertThat(first.jurisdiction()).containsEntry("country", "KR");
		assertThat(first.audience()).containsExactly("long_stay_foreigner", "new_arrival");
		assertThat(first.effectiveFrom()).isNull();
		assertThat(first.chunks()).singleElement().satisfies(chunk -> {
			assertThat(chunk.chunkOrder()).isZero();
			assertThat(chunk.text()).startsWith("상황: 한국에 입국해");
		});
		assertThat(first.relations()).first().satisfies(relation -> {
			assertThat(relation.subject()).isEqualTo("90일 초과 체류 예정 외국인");
			assertThat(relation.predicate()).isEqualTo("requires");
			assertThat(relation.confidence()).isEqualByComparingTo("0.99");
		});
		assertThat(seedPackage.sources()).filteredOn(source -> source.supportingUrls().isEmpty()).hasSize(17);
		assertThat(seedPackage.sources()).filteredOn(source -> !source.supportingUrls().isEmpty()).hasSize(3);
	}

	@Test
	void returnsDefensiveImmutableCollections() throws IOException {
		KnowledgeSeedPackage seedPackage = parseResource();
		KnowledgeSeedPackage.Source first = seedPackage.sources().getFirst();
		KnowledgeSeedPackage.Source withSupportingUrls = seedPackage.sources().stream()
			.filter(source -> !source.supportingUrls().isEmpty())
			.findFirst()
			.orElseThrow();

		assertThatThrownBy(() -> seedPackage.predicateVocabulary().add("new_predicate"))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> seedPackage.sources().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> first.jurisdiction().put("country", "US"))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> first.regionContext().put("sido", "서울특별시"))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> first.audience().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> first.dependencies().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> withSupportingUrls.supportingUrls().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> first.chunks().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> first.relations().clear())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void constructorDefensivelyCopiesCallerCollections() throws IOException {
		KnowledgeSeedPackage parsed = parseResource();
		KnowledgeSeedPackage.Source original = parsed.sources().getFirst();
		Map<String, String> jurisdiction = new HashMap<>(original.jurisdiction());
		Map<String, String> regionContext = new HashMap<>(original.regionContext());
		List<String> audience = new ArrayList<>(original.audience());
		List<String> dependencies = new ArrayList<>(original.dependencies());
		List<String> supportingUrls = new ArrayList<>(original.supportingUrls());
		List<KnowledgeSeedPackage.Chunk> chunks = new ArrayList<>(original.chunks());
		List<KnowledgeSeedPackage.Relation> relations = new ArrayList<>(original.relations());
		KnowledgeSeedPackage.Source copied = new KnowledgeSeedPackage.Source(
			original.sourceKey(), original.displayName(), original.documentType(), original.answerEligible(),
			original.sourceGrade(), original.authorityLevel(), jurisdiction, original.geoScope(), regionContext,
			audience, dependencies, original.riskDomain(), original.retrievedAt(), original.verifiedAt(),
			original.effectiveFrom(), original.validUntil(), original.reviewIntervalDays(), original.canonicalUrl(),
			supportingUrls, original.contentHash(), chunks, relations
		);
		List<String> predicates = new ArrayList<>(parsed.predicateVocabulary());
		List<KnowledgeSeedPackage.Source> sources = new ArrayList<>(List.of(copied));
		KnowledgeSeedPackage copiedPackage = new KnowledgeSeedPackage(
			parsed.schemaVersion(), parsed.packageKey(), parsed.packageVersion(), parsed.canonicalDraft(),
			parsed.documentType(), parsed.answerEligible(), parsed.language(), parsed.embeddingPolicy(),
			parsed.manifestHash(), 1, predicates, parsed.reviewView(), sources
		);

		jurisdiction.put("country", "US");
		regionContext.put("sido", "서울특별시");
		audience.clear();
		dependencies.clear();
		supportingUrls.add("https://example.go.kr");
		chunks.clear();
		relations.clear();
		predicates.clear();
		sources.clear();

		assertThat(copied.jurisdiction()).containsEntry("country", "KR");
		assertThat(copied.regionContext()).isEmpty();
		assertThat(copied.audience()).isNotEmpty();
		assertThat(copied.dependencies()).isNotEmpty();
		assertThat(copied.supportingUrls())
			.containsExactlyElementsOf(original.supportingUrls())
			.doesNotContain("https://example.go.kr");
		assertThat(copied.chunks()).isNotEmpty();
		assertThat(copied.relations()).isNotEmpty();
		assertThat(copiedPackage.predicateVocabulary()).hasSize(10);
		assertThat(copiedPackage.sources()).containsExactly(copied);
	}

	@Test
	void calculatesTheExactManifestIndependentlyOfObjectKeyOrder() throws IOException {
		ObjectNode canonical = canonicalTree();
		ObjectNode untouched = canonical.deepCopy();
		KnowledgeSeedManifestHasher hasher = new KnowledgeSeedManifestHasher();
		assertThat(hasher.hash(canonical)).isEqualTo(EXPECTED_MANIFEST_HASH);
		assertThat(canonical).isEqualTo(untouched);

		JsonNode reversed = reverseObjectKeysRecursively(canonical);
		String reorderedJson = objectMapper.writeValueAsString(reversed);

		KnowledgeSeedPackage parsed = parse(reorderedJson);

		assertThat(parsed.manifestHash()).isEqualTo(EXPECTED_MANIFEST_HASH);
		assertThat(hasher.hash(reversed)).isEqualTo(EXPECTED_MANIFEST_HASH);
	}

	@Test
	void normalizesAbsentSupportingUrlsAndPreservesNullableDatesAfterRawManifestValidation() throws IOException {
		ObjectNode root = canonicalTree();
		ObjectNode source = firstSource(root);
		source.remove("supportingUrls");
		source.putNull("effectiveFrom");
		source.putNull("validUntil");
		root.put("manifestHash", new KnowledgeSeedManifestHasher().hash(root));

		KnowledgeSeedPackage parsed = parse(root.toString());

		assertThat(parsed.sources().getFirst().supportingUrls()).isEmpty();
		assertThat(parsed.sources().getFirst().effectiveFrom()).isNull();
		assertThat(parsed.sources().getFirst().validUntil()).isNull();
	}

	@Test
	void rejectsMalformedAndDuplicateKeyJson() throws IOException {
		String canonical = resourceText();
		String duplicateKey = canonical.replaceFirst(
			"\\\"schemaVersion\\\"\\s*:\\s*\\\"1\\.0\\\"\\s*,",
			"\"schemaVersion\": \"1.0\", \"schemaVersion\": \"1.0\","
		);

		assertRejected("{");
		assertRejected(duplicateKey);
	}

	@Test
	void rejectsUnknownFieldsAtEveryTypedObjectBoundary() throws IOException {
		ObjectNode rootUnknown = canonicalTree();
		rootUnknown.put("unexpected", true);

		ObjectNode sourceUnknown = canonicalTree();
		firstSource(sourceUnknown).put("unexpected", true);

		ObjectNode chunkUnknown = canonicalTree();
		firstChunk(chunkUnknown).put("unexpected", true);

		ObjectNode relationUnknown = canonicalTree();
		firstRelation(relationUnknown).put("unexpected", true);

		ObjectNode jurisdictionUnknown = canonicalTree();
		firstSource(jurisdictionUnknown).withObjectProperty("jurisdiction").put("sido", "서울특별시");

		ObjectNode regionUnknown = canonicalTree();
		firstSource(regionUnknown).withObjectProperty("regionContext").put("rawAddress", "비공개 주소");

		ObjectNode blankRegionValue = canonicalTree();
		firstSource(blankRegionValue).withObjectProperty("regionContext").put("sido", " ");

		for (ObjectNode invalid : List.of(
			rootUnknown,
			sourceUnknown,
			chunkUnknown,
			relationUnknown,
			jurisdictionUnknown,
			regionUnknown,
			blankRegionValue
		)) {
			invalid.put("manifestHash", new KnowledgeSeedManifestHasher().hash(invalid));
			assertRejected(invalid.toString());
		}
	}

	@Test
	void rejectsScalarTypeCoercionEvenWhenCallerMapperIsLenient() throws IOException {
		ObjectMapper lenientCaller = new ObjectMapper();
		KnowledgeSeedPackageParser strictParser = new KnowledgeSeedPackageParser(lenientCaller);
		lenientCaller.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

		ObjectNode sourceCountAsString = canonicalTree();
		sourceCountAsString.put("expectedSourceCount", "20");

		ObjectNode answerEligibleAsString = canonicalTree();
		answerEligibleAsString.put("answerEligible", "true");

		ObjectNode chunkOrderAsString = canonicalTree();
		firstChunk(chunkOrderAsString).put("chunkOrder", "0");

		ObjectNode confidenceAsString = canonicalTree();
		firstRelation(confidenceAsString).put("confidence", "0.99");

		ObjectNode schemaVersionAsNumber = canonicalTree();
		schemaVersionAsNumber.put("schemaVersion", 1.0);

		ObjectNode sourceKeyAsNumber = canonicalTree();
		firstSource(sourceKeyAsNumber).put("sourceKey", 123);

		ObjectNode predicateAsBoolean = canonicalTree();
		firstRelation(predicateAsBoolean).put("predicate", true);

		ObjectNode answerEligibleAsNumber = canonicalTree();
		answerEligibleAsNumber.put("answerEligible", 1);

		for (ObjectNode invalid : List.of(
			sourceCountAsString,
			answerEligibleAsString,
			chunkOrderAsString,
			confidenceAsString,
			schemaVersionAsNumber,
			sourceKeyAsNumber,
			predicateAsBoolean,
			answerEligibleAsNumber
		)) {
			refreshManifest(invalid);
			assertThatThrownBy(() -> strictParser.parse(input(invalid.toString())))
				.isInstanceOf(KnowledgeSeedPackageValidationException.class);
		}
	}

	@Test
	void rejectsExplicitNullForOptionalCollectionAndMissingNullableDateFields() throws IOException {
		ObjectNode explicitNullSupportingUrls = canonicalTree();
		firstSource(explicitNullSupportingUrls).putNull("supportingUrls");

		ObjectNode missingEffectiveFrom = canonicalTree();
		firstSource(missingEffectiveFrom).remove("effectiveFrom");

		ObjectNode missingValidUntil = canonicalTree();
		firstSource(missingValidUntil).remove("validUntil");

		for (ObjectNode invalid : List.of(
			explicitNullSupportingUrls,
			missingEffectiveFrom,
			missingValidUntil
		)) {
			refreshManifest(invalid);
			assertRejected(invalid.toString());
		}
	}

	@Test
	void normalizesDomainValidationFailures() throws IOException {
		ObjectNode invalid = canonicalTree();
		invalid.put("manifestHash", "0".repeat(64));

		assertThatThrownBy(() -> parser.parse(input(invalid.toString())))
			.isInstanceOf(KnowledgeSeedPackageValidationException.class)
			.hasMessageContaining("manifestHash");
	}

	private KnowledgeSeedPackage parseResource() throws IOException {
		try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
			assertThat(input).as("canonical knowledge seed resource").isNotNull();
			return parser.parse(input);
		}
	}

	private String resourceText() throws IOException {
		try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
			assertThat(input).as("canonical knowledge seed resource").isNotNull();
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private ObjectNode canonicalTree() throws IOException {
		return (ObjectNode) objectMapper.readTree(resourceText());
	}

	private KnowledgeSeedPackage parse(String json) {
		return parser.parse(input(json));
	}

	private void assertRejected(String json) {
		assertThatThrownBy(() -> parse(json))
			.isInstanceOf(KnowledgeSeedPackageValidationException.class);
	}

	private void refreshManifest(ObjectNode root) {
		root.put("manifestHash", new KnowledgeSeedManifestHasher().hash(root));
	}

	private ByteArrayInputStream input(String json) {
		return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
	}

	private ObjectNode firstSource(ObjectNode root) {
		return (ObjectNode) root.withArrayProperty("sources").get(0);
	}

	private ObjectNode firstChunk(ObjectNode root) {
		return (ObjectNode) firstSource(root).withArrayProperty("chunks").get(0);
	}

	private ObjectNode firstRelation(ObjectNode root) {
		return (ObjectNode) firstSource(root).withArrayProperty("relations").get(0);
	}

	private JsonNode reverseObjectKeysRecursively(JsonNode node) throws JsonProcessingException {
		if (node.isObject()) {
			List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
			fields.addAll(node.properties());
			Collections.reverse(fields);
			ObjectNode reversed = objectMapper.createObjectNode();
			for (Map.Entry<String, JsonNode> field : fields) {
				reversed.set(field.getKey(), reverseObjectKeysRecursively(field.getValue()));
			}
			return reversed;
		}
		if (node.isArray()) {
			ArrayNode array = objectMapper.createArrayNode();
			for (JsonNode element : node) {
				array.add(reverseObjectKeysRecursively(element));
			}
			return array;
		}
		return node.deepCopy();
	}
}
