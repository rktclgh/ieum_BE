package shinhan.fibri.ieum.ai.knowledge.packageimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbedding;

class KnowledgeSeedPersistencePlanFactoryTest {

	private static final String RESOURCE = "/knowledge/korea_long_stay_seed_v0.2.json";

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final KnowledgeSeedPersistencePlanFactory factory = new KnowledgeSeedPersistencePlanFactory(objectMapper);

	@Test
	void mapsCanonicalPackageToStableDatabaseRowsAndProvenance() throws IOException {
		PreparedKnowledgeSeedPackage prepared = canonicalPreparedPackage();

		KnowledgeSeedPersistencePlan plan = factory.create(prepared);

		assertThat(plan.packageKey()).isEqualTo("korea-long-stay-seed");
		assertThat(plan.packageVersion()).isEqualTo("2026-07-13.2");
		assertThat(plan.manifestHash())
			.isEqualTo("1df465ec6a31d2d41c52681a7ee6b0c56960062c80af004034920621d784b39b");
		assertThat(plan.expectedSourceCount()).isEqualTo(20);
		assertThat(plan.sources()).hasSize(20);
		assertThat(plan.sources()).flatExtracting(KnowledgeSeedPersistencePlan.SourceRow::relations).hasSize(50);
		assertThat(plan.sources())
			.extracting(KnowledgeSeedPersistencePlan.SourceRow::sourceKey)
			.containsExactlyElementsOf(prepared.sources().stream()
				.map(source -> source.source().sourceKey())
				.toList());
		for (int index = 0; index < plan.sources().size(); index++) {
			assertThat(planRelationSignatures(plan.sources().get(index).relations()))
				.containsExactlyElementsOf(seedRelationSignatures(
					prepared.sources().get(index).source().relations()
				));
		}

		KnowledgeSeedPersistencePlan.SourceRow first = plan.sources().getFirst();
		assertThat(first.sourceKey()).isEqualTo("kr-foreigner-registration-90-days");
		assertThat(first.externalRef())
			.isEqualTo("seed:korea-long-stay-seed:kr-foreigner-registration-90-days");
		assertThat(first.validUntilExclusive())
			.isEqualTo(OffsetDateTime.parse("2026-10-14T00:00:00+09:00"));
		assertThat(objectMapper.readTree(first.regionContextJson())).isEqualTo(objectMapper.readTree("{}"));

		JsonNode sourceMetadata = objectMapper.readTree(first.metadataJson());
		List<String> metadataFields = new ArrayList<>();
		sourceMetadata.fieldNames().forEachRemaining(metadataFields::add);
		assertThat(metadataFields).containsExactly(
			"schemaVersion", "packageKey", "packageVersion", "manifestHash", "expectedSourceCount",
			"sourceKey", "documentType", "sourceGrade", "authorityLevel", "jurisdiction", "audience",
			"dependencies", "riskDomain", "retrievedAt", "verifiedAt", "effectiveFrom",
			"reviewIntervalDays", "canonicalUrl", "supportingUrls"
		);
		assertThat(sourceMetadata.get("schemaVersion").asText()).isEqualTo("1.0");
		assertThat(sourceMetadata.get("packageKey").asText()).isEqualTo("korea-long-stay-seed");
		assertThat(sourceMetadata.get("packageVersion").asText()).isEqualTo("2026-07-13.2");
		assertThat(sourceMetadata.get("manifestHash").asText()).isEqualTo(plan.manifestHash());
		assertThat(sourceMetadata.get("expectedSourceCount").asInt()).isEqualTo(20);
		assertThat(sourceMetadata.get("sourceKey").asText()).isEqualTo(first.sourceKey());
		assertThat(sourceMetadata.get("effectiveFrom").isNull()).isTrue();
		assertThat(sourceMetadata.get("supportingUrls").isArray()).isTrue();

		assertThat(first.chunk().chunkOrder()).isZero();
		assertThat(first.chunk().content()).isEqualTo(prepared.sources().getFirst().source().chunks().getFirst().text());
		assertThat(first.chunk().embedding()).isSameAs(prepared.sources().getFirst().embedding());
		assertThat(objectMapper.readTree(first.chunk().metadataJson()).get("sourceKey").asText())
			.isEqualTo(first.sourceKey());
		assertThat(first.relations()).allSatisfy(relation -> {
			assertThat(relation.evidenceChunkOrder()).isZero();
			assertThat(relation.metadataJson()).contains("\"packageVersion\":\"2026-07-13.2\"");
		});

		KnowledgeSeedPersistencePlan.SourceRow airport = plan.sources().stream()
			.filter(source -> source.sourceKey().equals("kr-nps-incheon-airport-refund"))
			.findFirst()
			.orElseThrow();
		assertThat(objectMapper.readTree(airport.regionContextJson()).get("place").asText())
			.isEqualTo("인천국제공항");
	}

	@Test
	void returnsDefensiveImmutablePlanCollections() throws IOException {
		KnowledgeSeedPersistencePlan plan = factory.create(canonicalPreparedPackage());

		assertThatThrownBy(() -> plan.sources().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> plan.sources().getFirst().relations().clear())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void rejectsMultipleChunksInsteadOfSilentlyDroppingThem() throws IOException {
		KnowledgeSeedPackage canonical = canonicalPackage();
		KnowledgeSeedPackage.Source source = canonical.sources().getFirst();
		KnowledgeSeedPackage.Source withTwoChunks = new KnowledgeSeedPackage.Source(
			source.sourceKey(), source.displayName(), source.documentType(), source.answerEligible(),
			source.sourceGrade(), source.authorityLevel(), source.jurisdiction(), source.geoScope(),
			source.regionContext(), source.audience(), source.dependencies(), source.riskDomain(),
			source.retrievedAt(), source.verifiedAt(), source.effectiveFrom(), source.validUntil(),
			source.reviewIntervalDays(), source.canonicalUrl(), source.supportingUrls(), source.contentHash(),
			List.of(source.chunks().getFirst(), source.chunks().getFirst()), source.relations()
		);
		List<KnowledgeSeedPackage.Source> sources = new ArrayList<>(canonical.sources());
		sources.set(0, withTwoChunks);
		KnowledgeSeedPackage invalid = new KnowledgeSeedPackage(
			canonical.schemaVersion(), canonical.packageKey(), canonical.packageVersion(), canonical.canonicalDraft(),
			canonical.documentType(), canonical.answerEligible(), canonical.language(), canonical.embeddingPolicy(),
			canonical.manifestHash(), canonical.expectedSourceCount(), canonical.predicateVocabulary(),
			canonical.reviewView(), sources
		);

		assertThatThrownBy(() -> factory.create(prepared(invalid)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("source must contain exactly one chunk");
	}

	private PreparedKnowledgeSeedPackage canonicalPreparedPackage() throws IOException {
		return prepared(canonicalPackage());
	}

	private KnowledgeSeedPackage canonicalPackage() throws IOException {
		try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
			if (input == null) {
				throw new IOException("Missing canonical resource " + RESOURCE);
			}
			return new KnowledgeSeedPackageParser(objectMapper).parse(input);
		}
	}

	private PreparedKnowledgeSeedPackage prepared(KnowledgeSeedPackage seedPackage) {
		List<PreparedKnowledgeSeedPackage.PreparedSource> preparedSources = seedPackage.sources().stream()
			.map(source -> new PreparedKnowledgeSeedPackage.PreparedSource(
				source,
				new GeminiEmbedding(GeminiEmbedding.MODEL, embedding())
			))
			.toList();
		return new PreparedKnowledgeSeedPackage(seedPackage, preparedSources);
	}

	private List<String> planRelationSignatures(List<KnowledgeSeedPersistencePlan.RelationRow> relations) {
		return relations.stream()
			.map(relation -> relation.subject() + '\u001f' + relation.predicate() + '\u001f'
				+ relation.object() + '\u001f' + relation.evidenceChunkOrder())
			.toList();
	}

	private List<String> seedRelationSignatures(List<KnowledgeSeedPackage.Relation> relations) {
		return relations.stream()
			.map(relation -> relation.subject() + '\u001f' + relation.predicate() + '\u001f'
				+ relation.object() + '\u001f' + relation.evidenceChunkOrder())
			.toList();
	}

	private List<Float> embedding() {
		return new ArrayList<>(java.util.Collections.nCopies(GeminiEmbedding.DIMENSIONS, 0f));
	}
}
