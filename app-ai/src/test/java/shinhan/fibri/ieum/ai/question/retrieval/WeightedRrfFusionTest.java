package shinhan.fibri.ieum.ai.question.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeightedRrfFusionTest {

	private final VectorKnowledgeRetrievalConfig config = VectorKnowledgeRetrievalConfig.defaults();
	private final VectorKnowledgeScorer vectorScorer = new VectorKnowledgeScorer(config);
	private final WeightedRrfFusion fusion = new WeightedRrfFusion(config);
	private final Instant retrievedAt = Instant.parse("2026-07-14T01:02:03Z");

	@Test
	void fusesVectorOnlyKgOnlyAndBothLaneCandidatesWithWeightedRrf() {
		VectorKnowledgeEvidence vectorOnly = vectorEvidence(1L, 11L, 0.90d, 2);
		VectorKnowledgeEvidence bothLanes = vectorEvidence(3L, 33L, 0.99d, 1);
		KnowledgeGraphCandidate bothRelation = relation(
			303L,
			3L,
			33L,
			"아이돌봄서비스",
			"supports",
			"출산가정",
			"0.900000",
			GeoScope.general,
			null,
			"공통 근거"
		);
		KnowledgeGraphCandidate kgOnly = relation(
			202L,
			2L,
			22L,
			"출산가정",
			"requires",
			"주민등록등본",
			"0.800000",
			GeoScope.general,
			null,
			"KG 근거"
		);

		List<KnowledgeEvidence> fused = fusion.fuse(
			List.of(vectorOnly, bothLanes),
			List.of(bothRelation, kgOnly),
			request(GeoScope.general, RegionContext.empty()),
			retrievedAt
		);

		assertThat(fused)
			.extracting(item -> item.sourceId() + ":" + item.chunkId())
			.containsExactly("3:33", "1:11", "2:22");

		HybridKnowledgeEvidence both = (HybridKnowledgeEvidence) fused.get(0);
		assertThat(both.vectorRank()).isEqualTo(1);
		assertThat(both.kgRank()).isEqualTo(1);
		assertThat(both.semanticScore()).isEqualByComparingTo("0.980000");
		assertThat(both.finalScore()).isEqualByComparingTo("0.956000");
		assertThat(both.relationId()).isEqualTo(303L);
		assertThat(both.excerpt()).isEqualTo(
			"관계: 아이돌봄서비스 supports 출산가정\n근거: 공통 근거"
		);

		assertThat(fused.get(1)).isSameAs(vectorOnly);
		assertThat(fused.get(1).semanticScore()).isEqualByComparingTo("0.611290");
		assertThat(fused.get(1).finalScore()).isEqualByComparingTo("0.605726");
		assertThat(fused.get(1).relationId()).isNull();

		HybridKnowledgeEvidence onlyKg = (HybridKnowledgeEvidence) fused.get(2);
		assertThat(onlyKg.vectorRank()).isNull();
		assertThat(onlyKg.kgRank()).isEqualTo(2);
		assertThat(onlyKg.semanticScore()).isEqualByComparingTo("0.434194");
		assertThat(onlyKg.finalScore()).isEqualByComparingTo("0.437484");
		assertThat(onlyKg.relationId()).isEqualTo(202L);
		assertThatThrownBy(() -> fused.add(vectorOnly))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void deduplicatesBySourceAndChunkAndPreservesStrongestRelationDeterministically() {
		VectorKnowledgeEvidence vector = vectorEvidence(4L, 44L, 0.75d, 1);
		KnowledgeGraphCandidate weakFirst = relation(
			9L,
			4L,
			44L,
			"약한주체",
			"supports",
			"약한객체",
			"0.400000",
			GeoScope.general,
			null,
			"약한 근거"
		);
		KnowledgeGraphCandidate strongHigherId = relation(
			8L,
			4L,
			44L,
			"강한주체",
			"requires",
			"강한객체",
			"0.900000",
			GeoScope.general,
			null,
			"강한 근거"
		);
		KnowledgeGraphCandidate strongLowerId = relation(
			7L,
			4L,
			44L,
			"결정주체",
			"prevents",
			"결정객체",
			"0.900000",
			GeoScope.general,
			null,
			"결정 근거"
		);

		List<KnowledgeEvidence> fused = fusion.fuse(
			List.of(vector),
			List.of(weakFirst, strongHigherId, strongLowerId),
			request(GeoScope.general, RegionContext.empty()),
			retrievedAt
		);

		assertThat(fused).hasSize(1);
		HybridKnowledgeEvidence evidence = (HybridKnowledgeEvidence) fused.getFirst();
		assertThat(evidence.relationId()).isEqualTo(7L);
		assertThat(evidence.relationConfidence()).isEqualByComparingTo("0.900000");
		assertThat(evidence.kgRank()).isEqualTo(3);
		assertThat(evidence.semanticScore()).isEqualByComparingTo("0.968571");
		assertThat(evidence.finalScore()).isEqualByComparingTo("0.945143");
		assertThat(evidence.excerpt()).isEqualTo(
			"관계: 결정주체 prevents 결정객체\n근거: 결정 근거"
		);
	}

	@Test
	void rejectsNonCanonicalRrfParametersForCanonicalHybridVersion() {
		VectorKnowledgeRetrievalConfig nonCanonicalK = configWithRrf(61, 0.6d);
		VectorKnowledgeRetrievalConfig nonCanonicalWeight = configWithRrf(60, 0.5d);

		assertThatThrownBy(() -> new WeightedRrfFusion(nonCanonicalK))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("retrieval-v2-hybrid-kg1")
			.hasMessageContaining("k=60")
			.hasMessageContaining("vectorWeight=0.6");
		assertThatThrownBy(() -> new WeightedRrfFusion(nonCanonicalWeight))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("retrieval-v2-hybrid-kg1")
			.hasMessageContaining("k=60")
			.hasMessageContaining("vectorWeight=0.6");
	}

	private VectorKnowledgeRetrievalConfig configWithRrf(int rrfK, double vectorWeight) {
		return new VectorKnowledgeRetrievalConfig(
			config.retrievalConfigVersion(),
			config.globalOverfetch(),
			config.geoOverfetch(),
			config.laneCandidateLimit(),
			config.evidenceLimit(),
			rrfK,
			vectorWeight,
			config.localDecayKm(),
			config.placeSpecificDecayKm(),
			config.scopeWeights()
		);
	}

	@Test
	void reusesAuthorityAndLocalGeoPoliciesForKgOnlyEvidence() {
		KnowledgeGraphCandidate governmentLocal = relation(
			501L,
			5L,
			55L,
			"서울시",
			"supports",
			"돌봄서비스",
			"1.000000",
			GeoScope.local,
			20.0d,
			"지역 근거",
			"government"
		);

		HybridKnowledgeEvidence evidence = (HybridKnowledgeEvidence) fusion.fuse(
			List.of(),
			List.of(governmentLocal),
			request(GeoScope.local, new RegionContext("서울특별시", "종로구")),
			retrievedAt
		).getFirst();

		assertThat(evidence.semanticScore()).isEqualByComparingTo("0.460000");
		assertThat(evidence.geoScore()).isEqualByComparingTo("0.135335");
		assertThat(evidence.finalScore()).isEqualByComparingTo("0.362601");
		assertThat(evidence.distanceKm()).isEqualByComparingTo("20.000000");
	}

	@Test
	void ordersRoundedScoreTiesBySourceThenChunkRegardlessOfInputOrder() {
		VectorKnowledgeEvidence sourceTwo = vectorEvidenceWithScores(2L, 1L, "0.800000", "0.500000");
		VectorKnowledgeEvidence sourceOneChunkTwo = vectorEvidenceWithScores(
			1L,
			2L,
			"0.800000",
			"0.500000"
		);
		VectorKnowledgeEvidence sourceOneChunkOne = vectorEvidenceWithScores(
			1L,
			1L,
			"0.800000",
			"0.500000"
		);
		List<VectorKnowledgeEvidence> shuffled = new ArrayList<>(
			List.of(sourceTwo, sourceOneChunkTwo, sourceOneChunkOne)
		);
		Collections.shuffle(shuffled, new java.util.Random(41L));

		List<KnowledgeEvidence> fused = fusion.fuse(
			shuffled,
			List.of(),
			request(GeoScope.general, RegionContext.empty()),
			retrievedAt
		);

		assertThat(fused)
			.extracting(item -> item.sourceId() + ":" + item.chunkId())
			.containsExactly("1:1", "1:2", "2:1");
	}

	@Test
	void hybridResultKeepsImmutableCandidateAndEvidenceSnapshots() {
		KnowledgeEvidence item = vectorEvidence(1L, 1L, 1.0d, 1);
		List<KnowledgeEvidence> candidates = new ArrayList<>(List.of(item));
		List<KnowledgeEvidence> evidence = new ArrayList<>(List.of(item));

		HybridKnowledgeRetrievalResult result = new HybridKnowledgeRetrievalResult(
			WeightedRrfFusion.RETRIEVAL_CONFIG_VERSION,
			candidates,
			evidence
		);
		candidates.clear();
		evidence.clear();

		assertThat(result.retrievalConfigVersion()).isEqualTo("retrieval-v2-hybrid-kg1");
		assertThat(result.candidates()).containsExactly(item);
		assertThat(result.evidence()).containsExactly(item);
		assertThatThrownBy(() -> result.evidence().clear())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	private VectorKnowledgeEvidence vectorEvidence(
		long sourceId,
		long chunkId,
		double cosineSimilarity,
		int rank
	) {
		return vectorScorer.score(
			vectorCandidate(sourceId, chunkId, cosineSimilarity),
			rank,
			request(GeoScope.general, RegionContext.empty()),
			retrievedAt
		);
	}

	private VectorKnowledgeCandidate vectorCandidate(long sourceId, long chunkId, double cosineSimilarity) {
		return new VectorKnowledgeCandidate(
			sourceId,
			chunkId,
			"curated",
			"source-" + sourceId,
			"vector-content-" + chunkId,
			"community",
			"a".repeat(64),
			"https://example.com/source/" + sourceId,
			"transportation",
			"community",
			GeoScope.general,
			RegionContext.empty(),
			cosineSimilarity,
			null
		);
	}

	private VectorKnowledgeEvidence vectorEvidenceWithScores(
		long sourceId,
		long chunkId,
		String cosineSimilarity,
		String finalScore
	) {
		return new VectorKnowledgeEvidence(
			sourceId,
			chunkId,
			"curated",
			"source-" + sourceId,
			"vector-content-" + chunkId,
			"community",
			"a".repeat(64),
			"https://example.com/source/" + sourceId,
			"transportation",
			"community",
			GeoScope.general,
			new BigDecimal(cosineSimilarity),
			new BigDecimal("0.500000"),
			new BigDecimal("0.500000"),
			new BigDecimal(finalScore),
			null,
			retrievedAt
		);
	}

	private KnowledgeGraphCandidate relation(
		long relationId,
		long sourceId,
		long chunkId,
		String subject,
		String predicate,
		String object,
		String confidence,
		GeoScope sourceGeoScope,
		Double distanceKm,
		String excerpt
	) {
		return relation(
			relationId,
			sourceId,
			chunkId,
			subject,
			predicate,
			object,
			confidence,
			sourceGeoScope,
			distanceKm,
			excerpt,
			"community"
		);
	}

	private KnowledgeGraphCandidate relation(
		long relationId,
		long sourceId,
		long chunkId,
		String subject,
		String predicate,
		String object,
		String confidence,
		GeoScope sourceGeoScope,
		Double distanceKm,
		String excerpt,
		String sourceGrade
	) {
		return new KnowledgeGraphCandidate(
			subject,
			"subject",
			relationId,
			sourceId,
			chunkId,
			subject,
			predicate,
			object,
			new BigDecimal(confidence),
			"curated",
			"source-" + sourceId,
			excerpt,
			sourceGrade,
			"a".repeat(64),
			"https://example.com/source/" + sourceId,
			"transportation",
			sourceGeoScope,
			RegionContext.empty(),
			distanceKm
		);
	}

	private VectorKnowledgeRetrievalRequest request(GeoScope scope, RegionContext regionContext) {
		return new VectorKnowledgeRetrievalRequest(unitVector(), scope, null, regionContext);
	}

	private List<Float> unitVector() {
		List<Float> values = new ArrayList<>(Collections.nCopies(768, 0.0f));
		values.set(0, 1.0f);
		return values;
	}
}
