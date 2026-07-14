package shinhan.fibri.ieum.ai.question.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class VectorKnowledgeScorerTest {

	private final VectorKnowledgeRetrievalConfig config = VectorKnowledgeRetrievalConfig.defaults();
	private final VectorKnowledgeScorer scorer = new VectorKnowledgeScorer(config);
	private final Instant retrievedAt = Instant.parse("2026-07-13T03:04:05Z");

	@ParameterizedTest
	@MethodSource("queryScopeScores")
	void appliesCanonicalSemanticAndGeoWeights(GeoScope queryScope, String expectedFinalScore) {
		VectorKnowledgeEvidence evidence = scorer.score(
			candidate(1L, GeoScope.general, "curated", "community", null, RegionContext.empty()),
			1,
			request(queryScope, RegionContext.empty()),
			retrievedAt
		);

		assertThat(evidence.semanticScore()).isEqualByComparingTo("0.620000");
		assertThat(evidence.geoScore()).isEqualByComparingTo("0.500000");
		assertThat(evidence.finalScore()).isEqualByComparingTo(expectedFinalScore);
		assertThat(evidence.finalScore().scale()).isEqualTo(6);
	}

	@Test
	void appliesLocalTenKilometerAndPlaceSpecificTwoKilometerDecay() {
		VectorKnowledgeEvidence local = scorer.score(
			candidate(1L, GeoScope.local, "curated", "community", 20.0d, RegionContext.empty()),
			1,
			request(GeoScope.local, RegionContext.empty()),
			retrievedAt
		);
		VectorKnowledgeEvidence placeSpecific = scorer.score(
			candidate(2L, GeoScope.place_specific, "curated", "community", 4.0d, RegionContext.empty()),
			2,
			request(GeoScope.place_specific, RegionContext.empty()),
			retrievedAt
		);

		assertThat(local.geoScore()).isEqualByComparingTo("0.135335");
		assertThat(placeSpecific.geoScore()).isEqualByComparingTo("0.135335");
	}

	@ParameterizedTest
	@MethodSource("authorityScores")
	void appliesCanonicalAuthorityScores(String sourceType, String sourceGrade, String expectedSemanticScore) {
		VectorKnowledgeEvidence evidence = scorer.score(
			candidate(1L, GeoScope.general, sourceType, sourceGrade, null, RegionContext.empty()),
			1,
			request(GeoScope.general, RegionContext.empty()),
			retrievedAt
		);

		assertThat(evidence.semanticScore()).isEqualByComparingTo(expectedSemanticScore);
	}

	@Test
	void appliesRegionalHierarchyAndNeutralMissingLocation() {
		RegionContext seoulJongno = new RegionContext("서울특별시", "종로구");
		RegionContext seoulGangnam = new RegionContext("서울특별시", "강남구");
		RegionContext gyeonggi = new RegionContext("경기도", "수원시");

		assertThat(scoreRegional(seoulJongno, seoulJongno)).isEqualByComparingTo("1.000000");
		assertThat(scoreRegional(seoulJongno, seoulGangnam)).isEqualByComparingTo("0.700000");
		assertThat(scoreRegional(seoulJongno, gyeonggi)).isEqualByComparingTo("0.200000");
		assertThat(scoreRegional(seoulJongno, RegionContext.empty())).isEqualByComparingTo("0.500000");
	}

	@Test
	void generalQueryAlwaysUsesNeutralGeoScoreRegardlessOfSourceGeography() {
		RegionContext seoulJongno = new RegionContext("서울특별시", "종로구");
		List<VectorKnowledgeCandidate> candidates = List.of(
			candidate(1L, GeoScope.regional, "curated", "community", null, seoulJongno),
			candidate(2L, GeoScope.local, "curated", "community", 0.0d, RegionContext.empty()),
			candidate(3L, GeoScope.place_specific, "curated", "community", 100.0d, RegionContext.empty()),
			candidate(4L, GeoScope.general, "curated", "community", 0.0d, RegionContext.empty())
		);

		assertThat(candidates.stream()
				.map(candidate -> scorer.score(candidate, 1, request(GeoScope.general, seoulJongno), retrievedAt).geoScore())
			.toList())
			.containsOnly(new BigDecimal("0.500000"));
	}

	@Test
	void defaultConfigKeepsCanonicalBoundsAndVersion() {
		assertThat(config.retrievalConfigVersion()).isEqualTo("retrieval-v1");
		assertThat(config.globalOverfetch()).isEqualTo(100);
		assertThat(config.geoOverfetch()).isEqualTo(100);
		assertThat(config.laneCandidateLimit()).isEqualTo(20);
		assertThat(config.evidenceLimit()).isEqualTo(8);
		assertThat(config.rrfK()).isEqualTo(60);
		assertThat(config.vectorWeight()).isEqualTo(0.6d);
		assertThat(config.localDecayKm()).isEqualTo(10.0d);
		assertThat(config.placeSpecificDecayKm()).isEqualTo(2.0d);
	}

	@Test
	void preservesVectorOnlyRankTwoNumericBehaviorThroughCommonEvidenceContract() {
		VectorKnowledgeEvidence evidence = scorer.score(
			candidate(1L, GeoScope.general, "curated", "community", null, RegionContext.empty()),
			2,
			request(GeoScope.general, RegionContext.empty()),
			retrievedAt
		);

		assertThat(evidence).isInstanceOf(KnowledgeEvidence.class);
		assertThat(evidence.semanticScore()).isEqualByComparingTo("0.611290");
		assertThat(evidence.geoScore()).isEqualByComparingTo("0.500000");
		assertThat(evidence.finalScore()).isEqualByComparingTo("0.605726");
		assertThat(evidence.relationId()).isNull();
	}

	@Test
	void finalOrderingUsesRoundedScoreThenSourceAndChunkIds() {
		VectorKnowledgeEvidence sourceTwo = evidence(2L, 1L, "0.500000");
		VectorKnowledgeEvidence sourceOneChunkTwo = evidence(1L, 2L, "0.500000");
		VectorKnowledgeEvidence sourceOneChunkOne = evidence(1L, 1L, "0.500000");

		List<VectorKnowledgeEvidence> ordered = Stream.of(sourceTwo, sourceOneChunkTwo, sourceOneChunkOne)
			.sorted(VectorOnlyKnowledgeRetrievalService.finalOrder())
			.toList();

		assertThat(ordered)
			.extracting(item -> item.sourceId() + ":" + item.chunkId())
			.containsExactly("1:1", "1:2", "2:1");
	}

	private BigDecimal scoreRegional(RegionContext queryRegion, RegionContext sourceRegion) {
		return scorer.score(
			candidate(1L, GeoScope.regional, "curated", "community", null, sourceRegion),
			1,
			request(GeoScope.regional, queryRegion),
			retrievedAt
		).geoScore();
	}

	private VectorKnowledgeRetrievalRequest request(GeoScope scope, RegionContext regionContext) {
		return new VectorKnowledgeRetrievalRequest(
			unitVector(),
			scope,
			null,
			regionContext
		);
	}

	private VectorKnowledgeCandidate candidate(
		long sourceId,
		GeoScope sourceScope,
		String sourceType,
		String sourceGrade,
		Double distanceKm,
		RegionContext sourceRegion
	) {
		return new VectorKnowledgeCandidate(
			sourceId,
			sourceId,
			sourceType,
			"source-" + sourceId,
			"content-" + sourceId,
			sourceGrade,
			"a".repeat(64),
			"https://example.com/source/" + sourceId,
			"transportation",
			"community",
			sourceScope,
			sourceRegion,
			1.0d,
			distanceKm
		);
	}

	private VectorKnowledgeEvidence evidence(long sourceId, long chunkId, String finalScore) {
		return new VectorKnowledgeEvidence(
			sourceId,
			chunkId,
			"curated",
			"source",
			"content",
			"community",
			"a".repeat(64),
			"https://example.com/source",
			"transportation",
			"community",
			GeoScope.general,
			new BigDecimal("1.000000"),
			new BigDecimal("0.500000"),
			new BigDecimal("0.500000"),
			new BigDecimal(finalScore),
			null,
			retrievedAt
		);
	}

	private List<Float> unitVector() {
		List<Float> values = new java.util.ArrayList<>(java.util.Collections.nCopies(768, 0.0f));
		values.set(0, 1.0f);
		return values;
	}

	private static Stream<Arguments> queryScopeScores() {
		return Stream.of(
			Arguments.of(GeoScope.general, "0.614000"),
			Arguments.of(GeoScope.regional, "0.596000"),
			Arguments.of(GeoScope.local, "0.584000"),
			Arguments.of(GeoScope.place_specific, "0.572000")
		);
	}

	private static Stream<Arguments> authorityScores() {
		return Stream.of(
			Arguments.of("curated", "government", "0.640000"),
			Arguments.of("curated", "public_agency", "0.640000"),
			Arguments.of("curated", "community", "0.620000"),
			Arguments.of("verified_external", null, "0.630000"),
			Arguments.of("accepted_human_answer", null, "0.610000")
		);
	}
}
