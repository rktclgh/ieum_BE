package shinhan.fibri.ieum.ai.question.retrieval;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class WeightedRrfFusion {

	public static final String RETRIEVAL_CONFIG_VERSION = "retrieval-v3-hybrid-kg2";
	private static final int CANONICAL_RRF_K = 60;
	private static final double CANONICAL_VECTOR_WEIGHT = 0.6d;

	private static final Comparator<VectorKnowledgeEvidence> VECTOR_ORDER = Comparator
		.comparing(VectorKnowledgeEvidence::cosineSimilarity)
		.reversed()
		.thenComparingLong(VectorKnowledgeEvidence::sourceId)
		.thenComparingLong(VectorKnowledgeEvidence::chunkId);

	private final KnowledgeRetrievalScorePolicy scorePolicy;

	public WeightedRrfFusion(VectorKnowledgeRetrievalConfig config) {
		Objects.requireNonNull(config, "config must not be null");
		if (config.rrfK() != CANONICAL_RRF_K
			|| Double.compare(config.vectorWeight(), CANONICAL_VECTOR_WEIGHT) != 0) {
			throw new IllegalArgumentException(
				RETRIEVAL_CONFIG_VERSION + " requires k=60 and vectorWeight=0.6"
			);
		}
		this.scorePolicy = new KnowledgeRetrievalScorePolicy(config);
	}

	public List<KnowledgeEvidence> fuse(
		List<VectorKnowledgeEvidence> vectorCandidates,
		List<KnowledgeGraphCandidate> kgCandidates,
		VectorKnowledgeRetrievalRequest request,
		Instant retrievedAt
	) {
		Objects.requireNonNull(request, "request must not be null");
		Objects.requireNonNull(retrievedAt, "retrievedAt must not be null");
		Map<EvidenceKey, RankedVector> vectors = rankVectors(vectorCandidates);
		Map<EvidenceKey, RankedRelation> relations = rankAndDeduplicateRelations(kgCandidates);
		Set<EvidenceKey> keys = new LinkedHashSet<>(vectors.keySet());
		keys.addAll(relations.keySet());

		List<KnowledgeEvidence> fused = new ArrayList<>(keys.size());
		for (EvidenceKey key : keys) {
			RankedVector vector = vectors.get(key);
			RankedRelation relation = relations.get(key);
			if (relation == null) {
				fused.add(vector.evidence());
			}
			else {
				fused.add(hybridEvidence(vector, relation, request, retrievedAt));
			}
		}
		return fused.stream().sorted(finalOrder()).toList();
	}

	public String retrievalConfigVersion() {
		return RETRIEVAL_CONFIG_VERSION;
	}

	static Comparator<KnowledgeEvidence> finalOrder() {
		return Comparator.comparing(KnowledgeEvidence::finalScore)
			.reversed()
			.thenComparingLong(KnowledgeEvidence::sourceId)
			.thenComparingLong(KnowledgeEvidence::chunkId)
			.thenComparing(
				KnowledgeEvidence::relationId,
				Comparator.nullsLast(Comparator.naturalOrder())
			);
	}

	private Map<EvidenceKey, RankedVector> rankVectors(List<VectorKnowledgeEvidence> candidates) {
		List<VectorKnowledgeEvidence> ordered = List.copyOf(candidates).stream()
			.sorted(VECTOR_ORDER)
			.toList();
		Map<EvidenceKey, RankedVector> ranked = new LinkedHashMap<>();
		for (int index = 0; index < ordered.size(); index++) {
			VectorKnowledgeEvidence evidence = ordered.get(index);
			ranked.putIfAbsent(
				new EvidenceKey(evidence.sourceId(), evidence.chunkId()),
				new RankedVector(evidence, index + 1)
			);
		}
		return ranked;
	}

	private Map<EvidenceKey, RankedRelation> rankAndDeduplicateRelations(
		List<KnowledgeGraphCandidate> candidates
	) {
		List<KnowledgeGraphCandidate> snapshot = List.copyOf(candidates);
		Map<EvidenceKey, RankedRelation> ranked = new LinkedHashMap<>();
		for (int index = 0; index < snapshot.size(); index++) {
			KnowledgeGraphCandidate candidate = snapshot.get(index);
			EvidenceKey key = new EvidenceKey(candidate.sourceId(), candidate.chunkId());
			RankedRelation replacement = new RankedRelation(candidate, index + 1);
			ranked.merge(key, replacement, this::strongestRelation);
		}
		return ranked;
	}

	private RankedRelation strongestRelation(RankedRelation existing, RankedRelation replacement) {
		int confidence = replacement.candidate().relationConfidence()
			.compareTo(existing.candidate().relationConfidence());
		if (confidence > 0) {
			return replacement;
		}
		if (confidence < 0) {
			return existing;
		}
		return replacement.candidate().relationId() < existing.candidate().relationId()
			? replacement
			: existing;
	}

	private HybridKnowledgeEvidence hybridEvidence(
		RankedVector rankedVector,
		RankedRelation rankedRelation,
		VectorKnowledgeRetrievalRequest request,
		Instant retrievedAt
	) {
		VectorKnowledgeEvidence vector = rankedVector == null ? null : rankedVector.evidence();
		KnowledgeGraphCandidate relation = rankedRelation.candidate();
		Integer vectorRank = rankedVector == null ? null : rankedVector.rank();
		KnowledgeRetrievalScorePolicy.Scores scores = scorePolicy.score(
			vector == null ? relation.sourceType() : vector.sourceType(),
			vector == null ? relation.sourceGrade() : vector.sourceGrade(),
			relation.sourceGeoScope(),
			relation.sourceRegionContext(),
			relation.distanceKm(),
			vectorRank,
			rankedRelation.rank(),
			relation.relationConfidence(),
			request
		);
		BigDecimal distanceKm = relation.distanceKm() == null
			? null
			: scorePolicy.round(relation.distanceKm());
		Instant evidenceRetrievedAt = vector == null ? retrievedAt : vector.retrievedAt();

		return new HybridKnowledgeEvidence(
			relation.sourceId(),
			relation.chunkId(),
			vector == null ? relation.sourceType() : vector.sourceType(),
			vector == null ? relation.title() : vector.title(),
			relationExcerpt(relation),
			vector == null ? relation.sourceGrade() : vector.sourceGrade(),
			vector == null ? relation.contentHash() : vector.contentHash(),
			vector == null ? relation.canonicalUrl() : vector.canonicalUrl(),
			vector == null ? relation.riskDomain() : vector.riskDomain(),
			vector == null ? null : vector.domain(),
			vector == null ? relation.sourceGeoScope() : vector.sourceGeoScope(),
			vector == null ? null : vector.cosineSimilarity(),
			vectorRank,
			rankedRelation.rank(),
			relation.relationId(),
			relation.matchedEntity(),
			relation.matchedSide(),
			relation.subject(),
			relation.predicate(),
			relation.object(),
			relation.relationConfidence(),
			scores.semanticScore(),
			scores.geoScore(),
			scores.finalScore(),
			distanceKm,
			evidenceRetrievedAt
		);
	}

	private String relationExcerpt(KnowledgeGraphCandidate candidate) {
		return "관계: " + candidate.subject() + " " + candidate.predicate() + " " + candidate.object()
			+ "\n근거: " + candidate.excerpt();
	}

	private record EvidenceKey(long sourceId, long chunkId) {
	}

	private record RankedVector(VectorKnowledgeEvidence evidence, int rank) {
	}

	private record RankedRelation(KnowledgeGraphCandidate candidate, int rank) {
	}
}
