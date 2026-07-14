package shinhan.fibri.ieum.ai.question.retrieval;

import java.time.Instant;
import java.util.Objects;

public class VectorKnowledgeScorer {

	private final KnowledgeRetrievalScorePolicy scorePolicy;

	public VectorKnowledgeScorer(VectorKnowledgeRetrievalConfig config) {
		this.scorePolicy = new KnowledgeRetrievalScorePolicy(
			Objects.requireNonNull(config, "config must not be null")
		);
	}

	public VectorKnowledgeEvidence score(
		VectorKnowledgeCandidate candidate,
		int vectorRank,
		VectorKnowledgeRetrievalRequest request,
		Instant retrievedAt
	) {
		Objects.requireNonNull(candidate, "candidate must not be null");
		Objects.requireNonNull(request, "request must not be null");
		Objects.requireNonNull(retrievedAt, "retrievedAt must not be null");
		if (vectorRank <= 0) {
			throw new IllegalArgumentException("vectorRank must be positive");
		}

		KnowledgeRetrievalScorePolicy.Scores scores = scorePolicy.score(
			candidate.sourceType(),
			candidate.sourceGrade(),
			candidate.sourceGeoScope(),
			candidate.sourceRegionContext(),
			candidate.distanceKm(),
			vectorRank,
			null,
			request
		);

		return new VectorKnowledgeEvidence(
			candidate.sourceId(),
			candidate.chunkId(),
			candidate.sourceType(),
			candidate.title(),
			candidate.excerpt(),
			candidate.sourceGrade(),
			candidate.contentHash(),
			candidate.canonicalUrl(),
			candidate.riskDomain(),
			candidate.domain(),
			candidate.sourceGeoScope(),
			scorePolicy.round(candidate.cosineSimilarity()),
			scores.semanticScore(),
			scores.geoScore(),
			scores.finalScore(),
			candidate.distanceKm() == null ? null : scorePolicy.round(candidate.distanceKm()),
			retrievedAt
		);
	}
}
