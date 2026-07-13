package shinhan.fibri.ieum.ai.knowledge.packageimport;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class KnowledgeSeedPackageValidatorTest {

	private static final String RESOURCE = "/knowledge/korea_long_stay_seed_v0.2.json";

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final KnowledgeSeedManifestHasher manifestHasher = new KnowledgeSeedManifestHasher();
	private final KnowledgeSeedPackageValidator validator = new KnowledgeSeedPackageValidator(manifestHasher);

	@Test
	void acceptsCanonicalPackage() throws IOException {
		ObjectNode root = canonicalTree();
		KnowledgeSeedPackage seedPackage = packageFrom(root);

		assertThatCode(() -> validator.validate(root, seedPackage)).doesNotThrowAnyException();
	}

	@Test
	void rejectsManifestMismatch() throws IOException {
		ObjectNode root = canonicalTree();
		firstSource(root).put("displayName", "검증 없이 변경됨");

		assertRejected(root, packageFrom(root), "manifestHash");
	}

	@Test
	void rejectsInvalidManifestHashFormat() throws IOException {
		ObjectNode root = canonicalTree();
		root.put("manifestHash", "ABC");

		assertRejected(root, packageFrom(root), "manifestHash");
	}

	@TestFactory
	Stream<DynamicTest> rejectsInvalidPackageAndSourceMetadata() {
		return Stream.of(
			invalid("schema version", root -> root.put("schemaVersion", "2.0"), "schemaVersion"),
			invalid("package key", root -> root.put("packageKey", "Invalid Package"), "packageKey"),
			invalid("package version", root -> root.put("packageVersion", "latest"), "packageVersion"),
			invalid("package document type", root -> root.put("documentType", "review_view"), "documentType"),
			invalid("package answer eligibility", root -> root.put("answerEligible", false), "answerEligible"),
			invalid("language", root -> root.put("language", "en"), "language"),
			invalid("embedding policy", root -> root.put("embeddingPolicy", "per_chunk"), "embeddingPolicy"),
			invalid("empty predicate vocabulary", root -> root.set("predicateVocabulary", objectMapper.createArrayNode()), "predicateVocabulary"),
			invalid("blank predicate", root -> root.withArrayProperty("predicateVocabulary").set(0, objectMapper.getNodeFactory().textNode(" ")), "predicateVocabulary"),
			invalid("duplicate predicate", root -> root.withArrayProperty("predicateVocabulary").add(root.withArrayProperty("predicateVocabulary").get(0)), "predicateVocabulary"),
			invalid("non-positive expected count", root -> root.put("expectedSourceCount", 0), "expectedSourceCount"),
			invalid("source count mismatch", root -> root.withArrayProperty("sources").remove(0), "sources"),
			invalid("duplicate source key", root -> secondSource(root).set("sourceKey", firstSource(root).get("sourceKey")), "sourceKey"),
			invalid("duplicate source hash", root -> secondSource(root).set("contentHash", firstSource(root).get("contentHash")), "contentHash"),
			invalid("content hash format", root -> firstSource(root).put("contentHash", "ABC"), "contentHash"),
			invalid("blank source key", root -> firstSource(root).put("sourceKey", " "), "sourceKey"),
			invalid("blank display name", root -> firstSource(root).put("displayName", " "), "displayName"),
			invalid("long display name", root -> firstSource(root).put("displayName", "x".repeat(201)), "displayName"),
			invalid("long seed document key", root -> {
				root.put("packageKey", "a".repeat(250));
				firstSource(root).put("sourceKey", "b".repeat(250));
			}, "document key"),
			invalid("source document type", root -> firstSource(root).put("documentType", "seed_knowledge_pack"), "documentType"),
			invalid("source answer eligibility", root -> firstSource(root).put("answerEligible", false), "answerEligible"),
			invalid("weak source grade", root -> firstSource(root).put("sourceGrade", "community"), "sourceGrade"),
			invalid("authority", root -> firstSource(root).put("authorityLevel", "private"), "authorityLevel"),
			invalid("jurisdiction", root -> firstSource(root).withObjectProperty("jurisdiction").put("country", "US"), "jurisdiction"),
			invalid("geo scope", root -> firstSource(root).put("geoScope", "worldwide"), "geoScope"),
			invalid("scoped region", root -> {
				firstSource(root).put("geoScope", "regional");
				firstSource(root).set("regionContext", objectMapper.createObjectNode());
			}, "regionContext"),
			invalid("empty audience", root -> firstSource(root).set("audience", objectMapper.createArrayNode()), "audience"),
			invalid("blank dependency", root -> firstSource(root).withArrayProperty("dependencies").add(" "), "dependencies"),
			invalid("blank risk domain", root -> firstSource(root).put("riskDomain", " "), "riskDomain"),
			invalid("review interval", root -> firstSource(root).put("reviewIntervalDays", 0), "reviewIntervalDays")
		).map(this::dynamicTest);
	}

	@TestFactory
	Stream<DynamicTest> rejectsInvalidDatesAndUrls() {
		return Stream.of(
			invalid("retrieved date", root -> firstSource(root).put("retrievedAt", "2026-02-30"), "retrievedAt"),
			invalid("verified date", root -> firstSource(root).put("verifiedAt", "13-07-2026"), "verifiedAt"),
			invalid("effective date", root -> firstSource(root).put("effectiveFrom", "tomorrow"), "effectiveFrom"),
			invalid("valid until", root -> firstSource(root).put("validUntil", "2026-13-01"), "validUntil"),
			invalid("canonical URL scheme", root -> firstSource(root).put("canonicalUrl", "http://example.go.kr"), "canonicalUrl"),
			invalid("canonical URL absolute", root -> firstSource(root).put("canonicalUrl", "https:/relative"), "canonicalUrl"),
			invalid("canonical URL user info", root -> firstSource(root).put("canonicalUrl", "https://user@example.go.kr/path"), "canonicalUrl"),
			invalid("supporting URL", root -> {
				ArrayNode urls = objectMapper.createArrayNode().add("http://example.go.kr");
				firstSource(root).set("supportingUrls", urls);
			}, "supportingUrls")
		).map(this::dynamicTest);
	}

	@TestFactory
	Stream<DynamicTest> rejectsInvalidChunksAndRelations() {
		return Stream.of(
			invalid("multiple chunks", root -> firstSource(root).withArrayProperty("chunks").add(firstChunk(root).deepCopy()), "chunks"),
			invalid("chunk order", root -> firstChunk(root).put("chunkOrder", 1), "chunkOrder"),
			invalid("blank chunk", root -> firstChunk(root).put("text", " "), "chunk text"),
			invalid("content hash", root -> firstSource(root).put("contentHash", "0".repeat(64)), "contentHash"),
			invalid("empty relations", root -> firstSource(root).set("relations", objectMapper.createArrayNode()), "relations"),
			invalid("unknown predicate", root -> firstRelation(root).put("predicate", "governs"), "predicate"),
			invalid("long predicate", root -> {
				String predicate = "p".repeat(121);
				root.withArrayProperty("predicateVocabulary").set(0, objectMapper.getNodeFactory().textNode(predicate));
				firstRelation(root).put("predicate", predicate);
			}, "predicate"),
			invalid("blank subject", root -> firstRelation(root).put("subject", " "), "subject"),
			invalid("long subject", root -> firstRelation(root).put("subject", "s".repeat(201)), "subject"),
			invalid("blank object", root -> firstRelation(root).put("object", " "), "object"),
			invalid("long object", root -> firstRelation(root).put("object", "o".repeat(201)), "object"),
			invalid("self edge", root -> firstRelation(root).set("object", firstRelation(root).get("subject")), "self"),
			invalid("negative confidence", root -> firstRelation(root).put("confidence", -0.01), "confidence"),
			invalid("excess confidence", root -> firstRelation(root).put("confidence", 1.01), "confidence"),
			invalid("evidence order", root -> firstRelation(root).put("evidenceChunkOrder", 1), "evidenceChunkOrder"),
			invalid("duplicate triple", root -> firstSource(root).withArrayProperty("relations").add(firstRelation(root).deepCopy()), "duplicate relation")
		).map(this::dynamicTest);
	}

	private DynamicTest dynamicTest(InvalidCase invalidCase) {
		return DynamicTest.dynamicTest(invalidCase.name(), () -> {
			ObjectNode root = canonicalTree();
			invalidCase.mutation().accept(root);
			refreshManifest(root);

			assertRejected(root, packageFrom(root), invalidCase.messageFragment());
		});
	}

	private InvalidCase invalid(String name, Consumer<ObjectNode> mutation, String messageFragment) {
		return new InvalidCase(name, mutation, messageFragment);
	}

	private void refreshManifest(ObjectNode root) {
		root.put("manifestHash", manifestHasher.hash(root));
	}

	private void assertRejected(JsonNode root, KnowledgeSeedPackage seedPackage, String messageFragment) {
		assertThatThrownBy(() -> validator.validate(root, seedPackage))
			.isInstanceOf(KnowledgeSeedPackageValidationException.class)
			.hasMessageContaining(messageFragment);
	}

	private ObjectNode canonicalTree() throws IOException {
		try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
			if (input == null) {
				throw new IOException("Missing canonical resource " + RESOURCE);
			}
			return (ObjectNode) objectMapper.readTree(input);
		}
	}

	private KnowledgeSeedPackage packageFrom(JsonNode root) throws IOException {
		return objectMapper.treeToValue(root, KnowledgeSeedPackage.class);
	}

	private ObjectNode firstSource(ObjectNode root) {
		return (ObjectNode) root.withArrayProperty("sources").get(0);
	}

	private ObjectNode secondSource(ObjectNode root) {
		return (ObjectNode) root.withArrayProperty("sources").get(1);
	}

	private ObjectNode firstChunk(ObjectNode root) {
		return (ObjectNode) firstSource(root).withArrayProperty("chunks").get(0);
	}

	private ObjectNode firstRelation(ObjectNode root) {
		return (ObjectNode) firstSource(root).withArrayProperty("relations").get(0);
	}

	private record InvalidCase(String name, Consumer<ObjectNode> mutation, String messageFragment) {
	}
}
