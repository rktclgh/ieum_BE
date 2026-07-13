package shinhan.fibri.ieum.ai.question.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class JdbcVectorOnlyKnowledgeRetrievalIntegrationTest {

	private static final String DATABASE = "ieum_ai_vector_retrieval";
	private static final GeoPoint SEOUL = new GeoPoint(37.5665, 126.9780);
	private static final Instant RETRIEVED_AT = Instant.parse("2026-07-13T03:04:05Z");

	private JdbcClient jdbc;
	private JdbcVectorKnowledgeRepository repository;
	private VectorOnlyKnowledgeRetrievalService service;

	@BeforeAll
	static void setUpSchema() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE)).sql("""
			ALTER TABLE knowledge_chunks DROP CONSTRAINT ck_knowledge_chunks_embedding_model
			""").update();
	}

	@BeforeEach
	void setUp() {
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		jdbc.sql("TRUNCATE knowledge_sources RESTART IDENTITY CASCADE").update();
		repository = new JdbcVectorKnowledgeRepository(jdbc);
		service = new VectorOnlyKnowledgeRetrievalService(
			repository,
			VectorKnowledgeRetrievalConfig.defaults(),
			Clock.fixed(RETRIEVED_AT, ZoneOffset.UTC)
		);
	}

	@Test
	void mapsCanonicalProvenanceAndStampsOneRetrievalInstant() {
		String contentHash = "b".repeat(64);
		Long sourceId = insertSource(source("Korea public service")
			.withContentHash(contentHash)
			.withSourceGrade("government")
			.withCanonicalUrl("https://www.gov.kr/service?id=1")
			.withRiskDomain("administration")
			.withDomain("public-service"));
		Long chunkId = insertChunk(sourceId, vector(1.0f), "gemini-embedding-2");

		VectorKnowledgeCandidate candidate = repository.findGlobalCandidates(vector(1.0f), 100).getFirst();
		VectorKnowledgeRetrievalResult result = service.retrieve(request(GeoScope.general, null));
		VectorKnowledgeEvidence evidence = result.evidence().getFirst();

		assertThat(candidate.sourceId()).isEqualTo(sourceId);
		assertThat(candidate.chunkId()).isEqualTo(chunkId);
		assertThat(candidate.contentHash()).isEqualTo(contentHash);
		assertThat(candidate.canonicalUrl()).isEqualTo("https://www.gov.kr/service?id=1");
		assertThat(candidate.riskDomain()).isEqualTo("administration");
		assertThat(candidate.domain()).isEqualTo("public-service");
		assertThat(candidate.title()).isEqualTo("Korea public service");
		assertThat(candidate.excerpt()).isEqualTo("content for " + sourceId);
		assertThat(evidence.contentHash()).isEqualTo(contentHash);
		assertThat(evidence.canonicalUrl()).isEqualTo(candidate.canonicalUrl());
		assertThat(evidence.retrievedAt()).isEqualTo(RETRIEVED_AT);
		assertThat(result.candidates()).allSatisfy(item -> assertThat(item.retrievedAt()).isEqualTo(RETRIEVED_AT));
		assertThat(service.revalidateEvidence(result.evidence()).getFirst().retrievedAt()).isEqualTo(RETRIEVED_AT);
	}

	@Test
	void globalLaneReturnsOnlyReadyActiveUnexpiredGeminiTwoChunks() {
		Long noExpiry = insertSource(source("eligible-no-expiry"));
		Long futureExpiry = insertSource(source("eligible-future")
			.withValidUntil(OffsetDateTime.now().plusDays(1)));
		Long failed = insertSource(source("failed").withStatus("failed"));
		Long inactive = insertSource(source("inactive"));
		jdbc.sql("UPDATE knowledge_sources SET active = false WHERE source_id = :sourceId")
			.param("sourceId", inactive)
			.update();
		Long expired = insertSource(source("expired")
			.withValidUntil(OffsetDateTime.now().minusMinutes(1)));
		Long oldModel = insertSource(source("old-model"));

		Long noExpiryChunk = insertChunk(noExpiry, vector(1.0f), "gemini-embedding-2");
		Long futureExpiryChunk = insertChunk(futureExpiry, vector(0.9f, 0.1f), "gemini-embedding-2");
		insertChunk(failed, vector(1.0f), "gemini-embedding-2");
		insertChunk(inactive, vector(1.0f), "gemini-embedding-2");
		insertChunk(expired, vector(1.0f), "gemini-embedding-2");
		insertChunk(oldModel, vector(1.0f), "gemini-embedding-001");

		List<VectorKnowledgeCandidate> candidates = repository.findGlobalCandidates(vector(1.0f), 100);

		assertThat(candidates)
			.extracting(VectorKnowledgeCandidate::chunkId)
			.containsExactly(noExpiryChunk, futureExpiryChunk);
	}

	@Test
	void globalLaneOrdersByCosineSimilarityWithStableIds() {
		Long exact = insertSource(source("exact"));
		Long partial = insertSource(source("partial"));
		Long orthogonal = insertSource(source("orthogonal"));
		Long exactChunk = insertChunk(exact, vector(1.0f), "gemini-embedding-2");
		Long partialChunk = insertChunk(partial, vector(0.8f, 0.6f), "gemini-embedding-2");
		Long orthogonalChunk = insertChunk(orthogonal, vector(0.0f, 1.0f), "gemini-embedding-2");

		List<VectorKnowledgeCandidate> candidates = repository.findGlobalCandidates(vector(1.0f), 100);

		assertThat(candidates)
			.extracting(VectorKnowledgeCandidate::chunkId)
			.containsExactly(exactChunk, partialChunk, orthogonalChunk);
		assertThat(candidates.get(0).cosineSimilarity()).isCloseTo(1.0d, within(0.000001d));
		assertThat(candidates.get(1).cosineSimilarity()).isCloseTo(0.8d, within(0.000001d));
		assertThat(candidates.get(2).cosineSimilarity()).isCloseTo(0.0d, within(0.000001d));
	}

	@Test
	void locationLaneKeepsLocalCandidateBeyondTenKilometers() {
		GeoPoint paju = new GeoPoint(37.7599, 126.7800);
		Long sourceId = insertSource(source("far-local")
			.withGeoScope(GeoScope.local)
			.withCoordinates(paju));
		insertChunk(sourceId, vector(1.0f), "gemini-embedding-2");

		List<VectorKnowledgeCandidate> locationCandidates = repository.findLocationAwareCandidates(
			vector(1.0f),
			RegionContext.empty(),
			SEOUL,
			100
		);
		VectorKnowledgeRetrievalResult result = service.retrieve(request(GeoScope.local, SEOUL));

		assertThat(locationCandidates).hasSize(1);
		assertThat(locationCandidates.getFirst().distanceKm()).isGreaterThan(10.0d);
		assertThat(result.evidence()).hasSize(1);
		VectorKnowledgeEvidence evidence = result.evidence().getFirst();
		assertThat(evidence.sourceId()).isEqualTo(sourceId);
		assertThat(evidence.distanceKm()).isGreaterThan(new BigDecimal("10.000000"));
		assertThat(evidence.geoScore())
			.isGreaterThan(BigDecimal.ZERO)
			.isLessThan(new BigDecimal("0.500000"));
	}

	@Test
	void locationLaneIncludesVeryNearLocalSourceOutsideGlobalSemanticTopHundred() {
		for (int index = 0; index < 100; index++) {
			Long sourceId = insertSource(source("semantic-global-" + index));
			insertChunk(sourceId, vector(1.0f, index / 1000.0f), "gemini-embedding-2");
		}
		Long nearbyLocal = insertSource(source("nearby-local")
			.withGeoScope(GeoScope.local)
			.withCoordinates(new GeoPoint(37.5666, 126.9780)));
		Long nearbyChunk = insertChunk(nearbyLocal, vector(0.0f, 1.0f), "gemini-embedding-2");

		List<VectorKnowledgeCandidate> globalCandidates = repository.findGlobalCandidates(vector(1.0f), 100);
		List<VectorKnowledgeCandidate> locationCandidates = repository.findLocationAwareCandidates(
			vector(1.0f),
			RegionContext.empty(),
			SEOUL,
			100
		);
		VectorKnowledgeRetrievalResult result = service.retrieve(request(GeoScope.local, SEOUL));

		assertThat(globalCandidates)
			.extracting(VectorKnowledgeCandidate::chunkId)
			.doesNotContain(nearbyChunk);
		assertThat(locationCandidates)
			.extracting(VectorKnowledgeCandidate::chunkId)
			.contains(nearbyChunk);
		assertThat(locationCandidates.getFirst().chunkId()).isEqualTo(nearbyChunk);
		assertThat(result.candidates())
			.extracting(VectorKnowledgeEvidence::chunkId)
			.contains(nearbyChunk);
		assertThat(result.candidates()).hasSize(21);
	}

	@Test
	void locationLaneIncludesMatchingRegionalSourceWithOrWithoutCoordinates() {
		RegionContext seoulJongno = new RegionContext("서울특별시", "종로구");
		Long regional = insertSource(source("matching-regional")
			.withGeoScope(GeoScope.regional)
			.withRegionContext(seoulJongno));
		Long regionalChunk = insertChunk(regional, vector(0.0f, 1.0f), "gemini-embedding-2");

		List<VectorKnowledgeCandidate> withoutCoordinates = repository.findLocationAwareCandidates(
			vector(1.0f),
			seoulJongno,
			null,
			100
		);
		List<VectorKnowledgeCandidate> withCoordinates = repository.findLocationAwareCandidates(
			vector(1.0f),
			seoulJongno,
			SEOUL,
			100
		);

		assertThat(withoutCoordinates)
			.extracting(VectorKnowledgeCandidate::chunkId)
			.contains(regionalChunk);
		assertThat(withCoordinates)
			.extracting(VectorKnowledgeCandidate::chunkId)
			.contains(regionalChunk);
	}

	@Test
	void generalSourcesHaveNoDistancePenalty() {
		Long near = insertSource(source("near-general")
			.withGeoScope(GeoScope.general)
			.withCoordinates(new GeoPoint(37.5670, 126.9780)));
		Long far = insertSource(source("far-general")
			.withGeoScope(GeoScope.general)
			.withCoordinates(new GeoPoint(35.1796, 129.0756)));
		insertChunk(near, vector(1.0f), "gemini-embedding-2");
		insertChunk(far, vector(0.99f, 0.01f), "gemini-embedding-2");

		VectorKnowledgeRetrievalResult result = service.retrieve(request(GeoScope.local, SEOUL));

		assertThat(result.candidates()).hasSize(2);
		assertThat(result.candidates())
			.extracting(VectorKnowledgeEvidence::geoScore)
			.containsOnly(new BigDecimal("0.500000"));
	}

	@Test
	void overfetchKeepsTwentyCandidatesPerLaneBeforeDeduplicatedUnion() {
		for (int index = 0; index < 105; index++) {
			Long sourceId = insertSource(source("bounded-global-" + index));
			insertChunk(sourceId, vector(1.0f, index / 1000.0f), "gemini-embedding-2");
			Long localSourceId = insertSource(source("bounded-geo-" + index)
				.withGeoScope(GeoScope.local)
				.withCoordinates(new GeoPoint(37.57, 126.98)));
			insertChunk(localSourceId, vector(0.0f, 1.0f), "gemini-embedding-2");
		}

		VectorKnowledgeRetrievalConfig config = VectorKnowledgeRetrievalConfig.defaults();
		List<VectorKnowledgeCandidate> overfetch = repository.findGlobalCandidates(
			vector(1.0f),
			config.globalOverfetch()
		);
		List<VectorKnowledgeCandidate> geoOverfetch = repository.findLocationAwareCandidates(
			vector(1.0f),
			RegionContext.empty(),
			SEOUL,
			config.geoOverfetch()
		);
		VectorKnowledgeRetrievalResult result = service.retrieve(request(GeoScope.general, SEOUL));

		assertThat(overfetch).hasSize(100);
		assertThat(geoOverfetch).hasSize(100);
		assertThat(result.candidates()).hasSize(config.laneCandidateLimit() * 2);
		assertThat(result.evidence()).hasSize(config.evidenceLimit());
	}

	@Test
	void duplicateChunkFromGlobalAndLocationLanesIsReturnedOnce() {
		Long sourceId = insertSource(source("two-lanes")
			.withGeoScope(GeoScope.local)
			.withCoordinates(new GeoPoint(37.57, 126.98)));
		Long chunkId = insertChunk(sourceId, vector(1.0f), "gemini-embedding-2");

		assertThat(repository.findGlobalCandidates(vector(1.0f), 100))
			.extracting(VectorKnowledgeCandidate::chunkId)
			.containsExactly(chunkId);
		assertThat(repository.findLocationAwareCandidates(
			vector(1.0f),
			RegionContext.empty(),
			SEOUL,
			100
		))
			.extracting(VectorKnowledgeCandidate::chunkId)
			.containsExactly(chunkId);
		VectorKnowledgeRetrievalResult result = service.retrieve(request(GeoScope.local, SEOUL));

		assertThat(result.candidates())
			.extracting(VectorKnowledgeEvidence::chunkId)
			.containsExactly(chunkId);
		assertThat(result.evidence()).hasSize(1);
	}

	@Test
	void revalidationDropsSourcesDeactivatedOrExpiredAfterRetrieval() {
		Long deactivated = insertSource(source("deactivated-after"));
		Long expired = insertSource(source("expired-after")
			.withValidUntil(OffsetDateTime.now().plusDays(1)));
		insertChunk(deactivated, vector(1.0f), "gemini-embedding-2");
		insertChunk(expired, vector(0.9f, 0.1f), "gemini-embedding-2");
		VectorKnowledgeRetrievalResult result = service.retrieve(request(GeoScope.general, null));
		assertThat(result.evidence()).hasSize(2);

		jdbc.sql("""
			UPDATE knowledge_sources
			SET active = false, status = 'inactive', deactivated_at = now()
			WHERE source_id = :sourceId
			""")
			.param("sourceId", deactivated)
			.update();
		jdbc.sql("UPDATE knowledge_sources SET valid_until = now() - interval '1 minute' WHERE source_id = :sourceId")
			.param("sourceId", expired)
			.update();

		assertThat(service.revalidateEvidence(result.candidates())).isEmpty();
		assertThat(repository.findEligibleChunkIds(
			result.candidates().stream().map(VectorKnowledgeEvidence::chunkId).toList()
		)).isEmpty();
	}

	@Test
	void revalidationDropsChunksDeletedOrChangedToAnotherEmbeddingModelAfterRetrieval() {
		Long deletedSource = insertSource(source("deleted-chunk-after"));
		Long changedModelSource = insertSource(source("changed-model-after"));
		Long deletedChunk = insertChunk(deletedSource, vector(1.0f), "gemini-embedding-2");
		Long changedModelChunk = insertChunk(changedModelSource, vector(0.9f, 0.1f), "gemini-embedding-2");
		VectorKnowledgeRetrievalResult result = service.retrieve(request(GeoScope.general, null));
		assertThat(result.evidence()).hasSize(2);

		jdbc.sql("DELETE FROM knowledge_chunks WHERE chunk_id = :chunkId")
			.param("chunkId", deletedChunk)
			.update();
		jdbc.sql("UPDATE knowledge_chunks SET embedding_model = 'gemini-embedding-001' WHERE chunk_id = :chunkId")
			.param("chunkId", changedModelChunk)
			.update();

		assertThat(service.revalidateEvidence(result.candidates())).isEmpty();
	}

	@Test
	void requestRejectsWrongSizedOrNonFiniteEmbedding() {
		assertThatThrownBy(() -> new VectorKnowledgeRetrievalRequest(
			List.of(1.0f),
			GeoScope.general,
			null,
			RegionContext.empty()
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("768");

		List<Float> nonFinite = vector(1.0f);
		nonFinite.set(300, Float.NaN);
		assertThatThrownBy(() -> new VectorKnowledgeRetrievalRequest(
			nonFinite,
			GeoScope.general,
			null,
			RegionContext.empty()
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("finite");
		assertThatThrownBy(() -> repository.findGlobalCandidates(nonFinite, 10))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("finite");
	}

	private VectorKnowledgeRetrievalRequest request(GeoScope scope, GeoPoint coordinates) {
		return new VectorKnowledgeRetrievalRequest(
			vector(1.0f),
			scope,
			coordinates,
			RegionContext.empty()
		);
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
			null,
			null
		);
	}

	private Long insertSource(SourceFixture source) {
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
			        'riskDomain', CAST(:riskDomain AS text), 'domain', CAST(:domain AS text)
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
			        'riskDomain', CAST(:riskDomain AS text), 'domain', CAST(:domain AS text)
			    ))
			)
			RETURNING source_id
			""";
		JdbcClient.StatementSpec statement = jdbc.sql(sql)
			.param("sourceType", source.sourceType())
			.param("externalRef", source.displayName())
			.param("displayName", source.displayName())
			.param("status", source.status())
			.param("active", source.active())
			.param("validUntil", source.validUntil())
			.param("geoScope", source.geoScope().name())
			.param("sido", source.regionContext().sido())
			.param("sigungu", source.regionContext().sigungu())
			.param("sourceGrade", source.sourceGrade())
			.param("contentHash", source.contentHash())
			.param("canonicalUrl", source.canonicalUrl())
			.param("riskDomain", source.riskDomain())
			.param("domain", source.domain());
		if (source.coordinates() != null) {
			statement = statement
				.param("latitude", source.coordinates().latitude())
				.param("longitude", source.coordinates().longitude());
		}
		return statement.query(Long.class).single();
	}

	private Long insertChunk(Long sourceId, List<Float> embedding, String embeddingModel) {
		return jdbc.sql("""
			INSERT INTO knowledge_chunks (source_id, content, embedding, embedding_model)
			VALUES (:sourceId, :content, CAST(:embedding AS vector), :embeddingModel)
			RETURNING chunk_id
			""")
			.param("sourceId", sourceId)
			.param("content", "content for " + sourceId)
			.param("embedding", vectorLiteral(embedding))
			.param("embeddingModel", embeddingModel)
			.query(Long.class)
			.single();
	}

	private List<Float> vector(float... leadingValues) {
		List<Float> vector = new ArrayList<>(java.util.Collections.nCopies(768, 0.0f));
		for (int index = 0; index < leadingValues.length; index++) {
			vector.set(index, leadingValues[index]);
		}
		return vector;
	}

	private String vectorLiteral(List<Float> vector) {
		return vector.stream()
			.map(String::valueOf)
			.collect(java.util.stream.Collectors.joining(",", "[", "]"));
	}

	private org.assertj.core.data.Offset<Double> within(double value) {
		return org.assertj.core.data.Offset.offset(value);
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
		String riskDomain,
		String domain
	) {
		private SourceFixture withStatus(String value) {
			return new SourceFixture(displayName, sourceType, value, active, validUntil, geoScope,
				regionContext, coordinates, sourceGrade, contentHash, canonicalUrl, riskDomain, domain);
		}

		private SourceFixture withValidUntil(OffsetDateTime value) {
			return new SourceFixture(displayName, sourceType, status, active, value, geoScope,
				regionContext, coordinates, sourceGrade, contentHash, canonicalUrl, riskDomain, domain);
		}

		private SourceFixture withGeoScope(GeoScope value) {
			return new SourceFixture(displayName, sourceType, status, active, validUntil, value,
				regionContext, coordinates, sourceGrade, contentHash, canonicalUrl, riskDomain, domain);
		}

		private SourceFixture withCoordinates(GeoPoint value) {
			return new SourceFixture(displayName, sourceType, status, active, validUntil, geoScope,
				regionContext, value, sourceGrade, contentHash, canonicalUrl, riskDomain, domain);
		}

		private SourceFixture withRegionContext(RegionContext value) {
			return new SourceFixture(displayName, sourceType, status, active, validUntil, geoScope,
				value, coordinates, sourceGrade, contentHash, canonicalUrl, riskDomain, domain);
		}

		private SourceFixture withSourceGrade(String value) {
			return new SourceFixture(displayName, sourceType, status, active, validUntil, geoScope,
				regionContext, coordinates, value, contentHash, canonicalUrl, riskDomain, domain);
		}

		private SourceFixture withContentHash(String value) {
			return new SourceFixture(displayName, sourceType, status, active, validUntil, geoScope,
				regionContext, coordinates, sourceGrade, value, canonicalUrl, riskDomain, domain);
		}

		private SourceFixture withCanonicalUrl(String value) {
			return new SourceFixture(displayName, sourceType, status, active, validUntil, geoScope,
				regionContext, coordinates, sourceGrade, contentHash, value, riskDomain, domain);
		}

		private SourceFixture withRiskDomain(String value) {
			return new SourceFixture(displayName, sourceType, status, active, validUntil, geoScope,
				regionContext, coordinates, sourceGrade, contentHash, canonicalUrl, value, domain);
		}

		private SourceFixture withDomain(String value) {
			return new SourceFixture(displayName, sourceType, status, active, validUntil, geoScope,
				regionContext, coordinates, sourceGrade, contentHash, canonicalUrl, riskDomain, value);
		}
	}
}
