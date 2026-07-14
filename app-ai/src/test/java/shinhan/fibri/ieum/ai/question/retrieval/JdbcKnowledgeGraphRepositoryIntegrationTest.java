package shinhan.fibri.ieum.ai.question.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class JdbcKnowledgeGraphRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_ai_kg_retrieval";
	private static final GeoPoint SEOUL = new GeoPoint(37.5665, 126.9780);

	private JdbcClient jdbc;
	private JdbcKnowledgeGraphRepository repository;

	@BeforeAll
	static void setUpSchema() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		JdbcClient schema = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		schema.sql("ALTER TABLE knowledge_chunks DROP CONSTRAINT ck_knowledge_chunks_embedding_model")
			.update();
		schema.sql("""
			ALTER TABLE knowledge_relations
			DROP CONSTRAINT fk_knowledge_relations_same_source_evidence
			""").update();
	}

	@BeforeEach
	void setUp() {
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		jdbc.sql("TRUNCATE knowledge_sources RESTART IDENTITY CASCADE").update();
		repository = new JdbcKnowledgeGraphRepository(jdbc);
	}

	@Test
	void findsExactSubjectAndObjectMatchesWithoutPartialOrSecondHopExpansion() {
		long sourceId = insertSource(source("canonical-source"));
		long chunkId = insertChunk(sourceId, "canonical evidence", "gemini-embedding-2");
		long subjectMatch = insertRelation(sourceId, "서울", "requires", "비자", "0.9500", chunkId);
		insertRelation(sourceId, "비자", "depends_on", "서류", "0.9900", chunkId);
		long objectMatch = insertRelation(sourceId, "정부", "located_in", "서울", "0.9000", chunkId);
		insertRelation(sourceId, "서울특별시", "supports", "생활", "0.9800", chunkId);

		List<KnowledgeGraphCandidate> result = repository.findOneHopCandidates(List.of("서울"), 20);

		assertThat(result)
			.extracting(KnowledgeGraphCandidate::relationId)
			.containsExactly(subjectMatch, objectMatch);
		assertThat(result)
			.extracting(KnowledgeGraphCandidate::matchedEntity)
			.containsOnly("서울");
		assertThat(result)
			.extracting(KnowledgeGraphCandidate::matchedSide)
			.containsExactly("subject", "object");
	}

	@Test
	void choosesEarliestMatchedEntityAndPrefersSubjectForASameEntityDualSideMatch() {
		long sourceId = insertSource(source("dual-side-source"));
		long chunkId = insertChunk(sourceId, "dual-side evidence", "gemini-embedding-2");
		long earlierObject = insertRelation(sourceId, "서울", "requires", "비자", "0.9500", chunkId);
		long dualSide = insertRelation(sourceId, "서울", "supports", "서울", "0.9000", chunkId);

		List<KnowledgeGraphCandidate> earlierEntity = repository.findOneHopCandidates(
			List.of("비자", "서울"),
			20
		);
		List<KnowledgeGraphCandidate> sameEntityBothSides = repository.findOneHopCandidates(
			List.of("서울"),
			20
		);

		assertThat(earlierEntity).filteredOn(item -> item.relationId() == earlierObject).singleElement()
			.satisfies(item -> {
				assertThat(item.matchedEntity()).isEqualTo("비자");
				assertThat(item.matchedSide()).isEqualTo("object");
			});
		assertThat(sameEntityBothSides).filteredOn(item -> item.relationId() == dualSide).singleElement()
			.satisfies(item -> assertThat(item.matchedSide()).isEqualTo("subject"));
	}

	@Test
	void filtersPredicateSourceChunkAndEvidenceEligibilityAtQueryTime() {
		java.util.ArrayList<Long> relationIds = new java.util.ArrayList<>();
		long eligibleSource = insertSource(source("eligible"));
		long eligibleChunk = insertChunk(eligibleSource, "eligible evidence", "gemini-embedding-2");
		long eligibleRelation = insertRelation(
			eligibleSource,
			"서울",
			"requires",
			"eligible",
			"0.9000",
			eligibleChunk
		);
		relationIds.add(eligibleRelation);

		long notReady = insertSource(source("not-ready").withStatus("failed"));
		long notReadyChunk = insertChunk(notReady, "not ready", "gemini-embedding-2");
		relationIds.add(insertRelation(
			notReady,
			"서울",
			"requires",
			"not-ready",
			"0.9900",
			notReadyChunk
		));

		long inactive = insertSource(source("inactive"));
		jdbc.sql("UPDATE knowledge_sources SET active = false WHERE source_id = :sourceId")
			.param("sourceId", inactive)
			.update();
		long inactiveChunk = insertChunk(inactive, "inactive", "gemini-embedding-2");
		relationIds.add(insertRelation(
			inactive,
			"서울",
			"requires",
			"inactive",
			"0.9900",
			inactiveChunk
		));

		long expired = insertSource(source("expired").withValidUntil(OffsetDateTime.now().minusMinutes(1)));
		long expiredChunk = insertChunk(expired, "expired", "gemini-embedding-2");
		relationIds.add(insertRelation(
			expired,
			"서울",
			"requires",
			"expired",
			"0.9900",
			expiredChunk
		));

		long wrongModel = insertSource(source("wrong-model"));
		long wrongModelChunk = insertChunk(wrongModel, "wrong model", "gemini-embedding-001");
		relationIds.add(insertRelation(
			wrongModel,
			"서울",
			"requires",
			"wrong-model",
			"0.9900",
			wrongModelChunk
		));

		relationIds.add(insertRelation(
			eligibleSource,
			"서울",
			"relates_to",
			"disallowed",
			"0.9900",
			eligibleChunk
		));
		relationIds.add(insertRelation(
			eligibleSource,
			"서울",
			"supports",
			"no-evidence",
			"0.9900",
			null
		));
		relationIds.add(insertRelation(
			eligibleSource,
			"서울",
			"used_for",
			"missing-evidence",
			"0.9900",
			999_999L
		));

		long otherSource = insertSource(source("other-source"));
		long otherChunk = insertChunk(otherSource, "other evidence", "gemini-embedding-2");
		relationIds.add(insertRelation(
			eligibleSource,
			"서울",
			"prevents",
			"wrong-source",
			"0.9900",
			otherChunk
		));

		assertThat(repository.findOneHopCandidates(List.of("서울"), 20))
			.extracting(KnowledgeGraphCandidate::relationId)
			.containsExactly(eligibleRelation);
		assertThat(repository.findEligibleRelationIds(relationIds)).containsExactly(eligibleRelation);
	}

	@Test
	void ordersByConfidenceThenInputRankAndStableIdsBeforeApplyingLimit() {
		long firstSource = insertSource(source("first-source"));
		long firstChunk = insertChunk(firstSource, "first evidence", "gemini-embedding-2");
		long lowerSourceTie = insertRelation(
			firstSource,
			"first-rank",
			"requires",
			"lower-source",
			"0.9000",
			firstChunk
		);

		long secondSource = insertSource(source("second-source"));
		long secondChunk = insertChunk(secondSource, "second evidence", "gemini-embedding-2");
		long earlierRank = insertRelation(
			secondSource,
			"second-rank",
			"requires",
			"earlier-rank",
			"0.9000",
			secondChunk
		);

		long thirdSource = insertSource(source("third-source"));
		long thirdChunk = insertChunk(thirdSource, "third evidence", "gemini-embedding-2");
		long higherConfidence = insertRelation(
			thirdSource,
			"first-rank",
			"requires",
			"higher-confidence",
			"0.9500",
			thirdChunk
		);

		long fourthSource = insertSource(source("fourth-source"));
		long fourthChunk = insertChunk(fourthSource, "fourth evidence", "gemini-embedding-2");
		long higherSourceTie = insertRelation(
			fourthSource,
			"first-rank",
			"requires",
			"higher-source",
			"0.9000",
			fourthChunk
		);

		List<String> entities = List.of("second-rank", "first-rank");

		assertThat(repository.findOneHopCandidates(entities, 20))
			.extracting(KnowledgeGraphCandidate::relationId)
			.containsExactly(higherConfidence, earlierRank, lowerSourceTie, higherSourceTie);
		assertThat(repository.findOneHopCandidates(entities, 2))
			.extracting(KnowledgeGraphCandidate::relationId)
			.containsExactly(higherConfidence, earlierRank);
	}

	@Test
	void usesChunkThenRelationIdsAsFinalStableTieBreakers() {
		long sourceId = insertSource(source("stable-ties"));
		long firstChunk = insertChunk(sourceId, "first chunk", "gemini-embedding-2", 0);
		long secondChunk = insertChunk(sourceId, "second chunk", "gemini-embedding-2", 1);
		long firstRelation = insertRelation(
			sourceId,
			"서울",
			"requires",
			"first relation",
			"0.9000",
			firstChunk
		);
		long secondRelation = insertRelation(
			sourceId,
			"서울",
			"supports",
			"second relation",
			"0.9000",
			firstChunk
		);
		long laterChunk = insertRelation(
			sourceId,
			"서울",
			"used_for",
			"later chunk",
			"0.9000",
			secondChunk
		);

		assertThat(repository.findOneHopCandidates(List.of("서울"), 20))
			.extracting(KnowledgeGraphCandidate::relationId)
			.containsExactly(firstRelation, secondRelation, laterChunk);
	}

	@Test
	void mapsCanonicalProvenanceAndComputesOptionalPostgisDistance() {
		SourceFixture local = source("서울 출입국 안내")
			.withGeoScope(GeoScope.local)
			.withRegionContext(new RegionContext("서울특별시", "종로구"))
			.withCoordinates(new GeoPoint(37.5666, 126.9780))
			.withSourceGrade("government")
			.withCanonicalUrl("https://www.gov.kr/service?id=1")
			.withRiskDomain("immigration")
			.withContentHash("b".repeat(64));
		long sourceId = insertSource(local);
		long chunkId = insertChunk(sourceId, "서울 비자 근거", "gemini-embedding-2");
		long relationId = insertRelation(sourceId, "서울", "requires", "비자", "0.8750", chunkId);

		KnowledgeGraphCandidate withCoordinates = repository.findOneHopCandidates(
			List.of("서울"),
			SEOUL,
			20
		).getFirst();
		KnowledgeGraphCandidate withoutCoordinates = repository.findOneHopCandidates(
			List.of("서울"),
			20
		).getFirst();

		assertThat(withCoordinates.matchedEntity()).isEqualTo("서울");
		assertThat(withCoordinates.matchedSide()).isEqualTo("subject");
		assertThat(withCoordinates.relationId()).isEqualTo(relationId);
		assertThat(withCoordinates.sourceId()).isEqualTo(sourceId);
		assertThat(withCoordinates.chunkId()).isEqualTo(chunkId);
		assertThat(withCoordinates.subject()).isEqualTo("서울");
		assertThat(withCoordinates.predicate()).isEqualTo("requires");
		assertThat(withCoordinates.object()).isEqualTo("비자");
		assertThat(withCoordinates.relationConfidence()).isEqualByComparingTo("0.8750");
		assertThat(withCoordinates.sourceType()).isEqualTo("curated");
		assertThat(withCoordinates.title()).isEqualTo("서울 출입국 안내");
		assertThat(withCoordinates.excerpt()).isEqualTo("서울 비자 근거");
		assertThat(withCoordinates.sourceGrade()).isEqualTo("government");
		assertThat(withCoordinates.contentHash()).isEqualTo("b".repeat(64));
		assertThat(withCoordinates.canonicalUrl()).isEqualTo("https://www.gov.kr/service?id=1");
		assertThat(withCoordinates.riskDomain()).isEqualTo("immigration");
		assertThat(withCoordinates.sourceGeoScope()).isEqualTo(GeoScope.local);
		assertThat(withCoordinates.sourceRegionContext())
			.isEqualTo(new RegionContext("서울특별시", "종로구"));
		assertThat(withCoordinates.distanceKm()).isCloseTo(0.011d, within(0.003d));
		assertThat(withoutCoordinates.distanceKm()).isNull();
	}

	@Test
	void revalidatesRelationsAgainstCurrentEligibility() {
		long sourceId = insertSource(source("revalidation-source"));
		long chunkId = insertChunk(sourceId, "revalidation evidence", "gemini-embedding-2");
		long relationId = insertRelation(sourceId, "서울", "requires", "비자", "0.9000", chunkId);
		long disallowed = insertRelation(sourceId, "서울", "relates_to", "행정", "0.9000", chunkId);

		assertThat(repository.findEligibleRelationIds(List.of(relationId, relationId, disallowed)))
			.containsExactly(relationId);

		jdbc.sql("UPDATE knowledge_sources SET active = false WHERE source_id = :sourceId")
			.param("sourceId", sourceId)
			.update();

		assertThat(repository.findEligibleRelationIds(List.of(relationId))).isEmpty();
		assertThat(repository.findEligibleRelationIds(List.of())).isEqualTo(Set.of());
	}

	@Test
	void rejectsInvalidRepositoryArgumentsBeforeQuerying() {
		assertThatThrownBy(() -> repository.findOneHopCandidates(null, 20))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("canonicalEntityCandidates");
		assertThatThrownBy(() -> repository.findOneHopCandidates(List.of(), 20))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("empty");
		assertThatThrownBy(() -> repository.findOneHopCandidates(List.of("서울"), 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("limit");
		assertThatThrownBy(() -> repository.findOneHopCandidates(List.of("서울"), 21))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("20");
		assertThatThrownBy(() -> repository.findEligibleRelationIds(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("relationIds");
	}

	private SourceFixture source(String displayName) {
		return new SourceFixture(
			displayName,
			"curated",
			"ready",
			true,
			null,
			GeoScope.general,
			RegionContext.empty(),
			null,
			"community",
			"a".repeat(64),
			null,
			null
		);
	}

	private long insertSource(SourceFixture source) {
		String sql = source.coordinates() == null ? """
			INSERT INTO knowledge_sources (
			    source_type, external_ref, content_hash, display_name, status, active,
			    valid_until, geo_scope, region_context, metadata
			)
			VALUES (
			    CAST(:sourceType AS knowledge_source_type), :externalRef, :contentHash,
			    :displayName, :status, :active, CAST(:validUntil AS timestamptz), :geoScope,
			    jsonb_strip_nulls(jsonb_build_object(
			        'sido', CAST(:sido AS text), 'sigungu', CAST(:sigungu AS text)
			    )),
			    jsonb_strip_nulls(jsonb_build_object(
			        'sourceGrade', :sourceGrade, 'canonicalUrl', CAST(:canonicalUrl AS text),
			        'riskDomain', CAST(:riskDomain AS text)
			    ))
			)
			RETURNING source_id
			""" : """
			INSERT INTO knowledge_sources (
			    source_type, external_ref, content_hash, display_name, status, active,
			    valid_until, geo_scope, region_context, anchor_location, metadata
			)
			VALUES (
			    CAST(:sourceType AS knowledge_source_type), :externalRef, :contentHash,
			    :displayName, :status, :active, CAST(:validUntil AS timestamptz), :geoScope,
			    jsonb_strip_nulls(jsonb_build_object(
			        'sido', CAST(:sido AS text), 'sigungu', CAST(:sigungu AS text)
			    )),
			    ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
			    jsonb_strip_nulls(jsonb_build_object(
			        'sourceGrade', :sourceGrade, 'canonicalUrl', CAST(:canonicalUrl AS text),
			        'riskDomain', CAST(:riskDomain AS text)
			    ))
			)
			RETURNING source_id
			""";
		JdbcClient.StatementSpec statement = jdbc.sql(sql)
			.param("sourceType", source.sourceType())
			.param("externalRef", source.displayName())
			.param("contentHash", source.contentHash())
			.param("displayName", source.displayName())
			.param("status", source.status())
			.param("active", source.active())
			.param("validUntil", source.validUntil())
			.param("geoScope", source.geoScope().name())
			.param("sido", source.regionContext().sido())
			.param("sigungu", source.regionContext().sigungu())
			.param("sourceGrade", source.sourceGrade())
			.param("canonicalUrl", source.canonicalUrl())
			.param("riskDomain", source.riskDomain());
		if (source.coordinates() != null) {
			statement = statement
				.param("latitude", source.coordinates().latitude())
				.param("longitude", source.coordinates().longitude());
		}
		return statement.query(Long.class).single();
	}

	private long insertChunk(long sourceId, String content, String embeddingModel) {
		return insertChunk(sourceId, content, embeddingModel, 0);
	}

	private long insertChunk(
		long sourceId,
		String content,
		String embeddingModel,
		int chunkOrder
	) {
		return jdbc.sql("""
			INSERT INTO knowledge_chunks (
			    source_id, content, chunk_order, embedding, embedding_model
			)
			VALUES (
			    :sourceId, :content, :chunkOrder,
			    array_fill(0.0::real, ARRAY[768])::vector, :embeddingModel
			)
			RETURNING chunk_id
			""")
			.param("sourceId", sourceId)
			.param("content", content)
			.param("chunkOrder", chunkOrder)
			.param("embeddingModel", embeddingModel)
			.query(Long.class)
			.single();
	}

	private long insertRelation(
		long sourceId,
		String subject,
		String predicate,
		String object,
		String confidence,
		Long evidenceChunkId
	) {
		return jdbc.sql("""
			INSERT INTO knowledge_relations (
			    source_id, subject, predicate, object, confidence, evidence_chunk_id
			)
			VALUES (
			    :sourceId, :subject, :predicate, :object, :confidence, CAST(:evidenceChunkId AS bigint)
			)
			RETURNING relation_id
			""")
			.param("sourceId", sourceId)
			.param("subject", subject)
			.param("predicate", predicate)
			.param("object", object)
			.param("confidence", new BigDecimal(confidence))
			.param("evidenceChunkId", evidenceChunkId)
			.query(Long.class)
			.single();
	}

	private record SourceFixture(
		String displayName,
		String sourceType,
		String status,
		boolean active,
		OffsetDateTime validUntil,
		GeoScope geoScope,
		RegionContext regionContext,
		GeoPoint coordinates,
		String sourceGrade,
		String contentHash,
		String canonicalUrl,
		String riskDomain
	) {
		private SourceFixture withStatus(String value) {
			return new SourceFixture(displayName, sourceType, value, active, validUntil, geoScope,
				regionContext, coordinates, sourceGrade, contentHash, canonicalUrl, riskDomain);
		}

		private SourceFixture withValidUntil(OffsetDateTime value) {
			return new SourceFixture(displayName, sourceType, status, active, value, geoScope,
				regionContext, coordinates, sourceGrade, contentHash, canonicalUrl, riskDomain);
		}

		private SourceFixture withGeoScope(GeoScope value) {
			return new SourceFixture(displayName, sourceType, status, active, validUntil, value,
				regionContext, coordinates, sourceGrade, contentHash, canonicalUrl, riskDomain);
		}

		private SourceFixture withRegionContext(RegionContext value) {
			return new SourceFixture(displayName, sourceType, status, active, validUntil, geoScope,
				value, coordinates, sourceGrade, contentHash, canonicalUrl, riskDomain);
		}

		private SourceFixture withCoordinates(GeoPoint value) {
			return new SourceFixture(displayName, sourceType, status, active, validUntil, geoScope,
				regionContext, value, sourceGrade, contentHash, canonicalUrl, riskDomain);
		}

		private SourceFixture withSourceGrade(String value) {
			return new SourceFixture(displayName, sourceType, status, active, validUntil, geoScope,
				regionContext, coordinates, value, contentHash, canonicalUrl, riskDomain);
		}

		private SourceFixture withContentHash(String value) {
			return new SourceFixture(displayName, sourceType, status, active, validUntil, geoScope,
				regionContext, coordinates, sourceGrade, value, canonicalUrl, riskDomain);
		}

		private SourceFixture withCanonicalUrl(String value) {
			return new SourceFixture(displayName, sourceType, status, active, validUntil, geoScope,
				regionContext, coordinates, sourceGrade, contentHash, value, riskDomain);
		}

		private SourceFixture withRiskDomain(String value) {
			return new SourceFixture(displayName, sourceType, status, active, validUntil, geoScope,
				regionContext, coordinates, sourceGrade, contentHash, canonicalUrl, value);
		}
	}
}
