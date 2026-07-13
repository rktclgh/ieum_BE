package shinhan.fibri.ieum.ai.question.retrieval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public class VectorKnowledgeScorer {

	private static final int SCORE_SCALE = 6;
	private static final double FUSION_SEMANTIC_WEIGHT = 0.9d;
	private static final double AUTHORITY_WEIGHT = 0.1d;
	private static final double NEUTRAL_GEO_SCORE = 0.5d;

	private final VectorKnowledgeRetrievalConfig config;

	public VectorKnowledgeScorer(VectorKnowledgeRetrievalConfig config) {
		this.config = Objects.requireNonNull(config, "config must not be null");
	}

	public VectorKnowledgeEvidence score(
		VectorKnowledgeCandidate candidate,
		int vectorRank,
		VectorKnowledgeRetrievalRequest request
	) {
		Objects.requireNonNull(candidate, "candidate must not be null");
		Objects.requireNonNull(request, "request must not be null");
		if (vectorRank <= 0) {
			throw new IllegalArgumentException("vectorRank must be positive");
		}

		double vectorRrf = 1.0d / (config.rrfK() + vectorRank);
		double fusionRaw = config.vectorWeight() * vectorRrf;
		double fusionNorm = clamp(fusionRaw * (config.rrfK() + 1.0d));
		double semanticScore = clamp(
			FUSION_SEMANTIC_WEIGHT * fusionNorm + AUTHORITY_WEIGHT * authorityScore(candidate)
		);
		double geoScore = request.geoScope() == GeoScope.general
			? NEUTRAL_GEO_SCORE
			: clamp(geoScore(candidate, request.regionContext()));
		VectorKnowledgeRetrievalConfig.ScoreWeights weights = config.weightsFor(request.geoScope());
		double finalScore = clamp(
			weights.semanticWeight() * semanticScore + weights.geoWeight() * geoScore
		);

		return new VectorKnowledgeEvidence(
			candidate.sourceId(),
			candidate.chunkId(),
			candidate.sourceType(),
			candidate.displayName(),
			candidate.content(),
			candidate.sourceGrade(),
			candidate.sourceGeoScope(),
			round(candidate.cosineSimilarity()),
			round(semanticScore),
			round(geoScore),
			round(finalScore),
			candidate.distanceKm() == null ? null : round(candidate.distanceKm())
		);
	}

	private double authorityScore(VectorKnowledgeCandidate candidate) {
		return switch (candidate.sourceType()) {
			case "verified_external" -> 0.9d;
			case "accepted_human_answer" -> 0.7d;
			case "curated" -> governmentGrade(candidate.sourceGrade()) ? 1.0d : 0.8d;
			default -> 0.8d;
		};
	}

	private boolean governmentGrade(String sourceGrade) {
		return "government".equals(sourceGrade) || "public_agency".equals(sourceGrade);
	}

	private double geoScore(VectorKnowledgeCandidate candidate, RegionContext queryRegion) {
		GeoScope sourceScope = candidate.sourceGeoScope();
		if (sourceScope == null) {
			return NEUTRAL_GEO_SCORE;
		}
		return switch (sourceScope) {
			case general -> NEUTRAL_GEO_SCORE;
			case regional -> regionalScore(queryRegion, candidate.sourceRegionContext());
			case local -> distanceDecay(candidate.distanceKm(), config.localDecayKm());
			case place_specific -> distanceDecay(candidate.distanceKm(), config.placeSpecificDecayKm());
		};
	}

	private double regionalScore(RegionContext query, RegionContext source) {
		if (query == null || source == null || !query.hasSido() || !source.hasSido()) {
			return NEUTRAL_GEO_SCORE;
		}
		if (query.hasSigungu() && source.hasSigungu()
			&& query.sido().equals(source.sido())
			&& query.sigungu().equals(source.sigungu())) {
			return 1.0d;
		}
		if (query.sido().equals(source.sido())) {
			return 0.7d;
		}
		return 0.2d;
	}

	private double distanceDecay(Double distanceKm, double scaleKm) {
		if (distanceKm == null || !Double.isFinite(distanceKm) || distanceKm < 0.0d) {
			return NEUTRAL_GEO_SCORE;
		}
		return Math.exp(-distanceKm / scaleKm);
	}

	private double clamp(double value) {
		if (!Double.isFinite(value)) {
			return 0.0d;
		}
		return Math.max(0.0d, Math.min(1.0d, value));
	}

	private BigDecimal round(double value) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException("score values must be finite");
		}
		return BigDecimal.valueOf(value).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
	}
}
