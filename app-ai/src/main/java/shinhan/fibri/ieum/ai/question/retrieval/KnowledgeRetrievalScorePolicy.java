package shinhan.fibri.ieum.ai.question.retrieval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class KnowledgeRetrievalScorePolicy {

	private static final int SCORE_SCALE = 6;
	private static final double FUSION_SEMANTIC_WEIGHT = 0.65d;
	private static final double AUTHORITY_WEIGHT = 0.35d;
	private static final double NEUTRAL_GEO_SCORE = 0.5d;

	private final VectorKnowledgeRetrievalConfig config;

	public KnowledgeRetrievalScorePolicy(VectorKnowledgeRetrievalConfig config) {
		this.config = Objects.requireNonNull(config, "config must not be null");
	}

	Scores score(
		String sourceType,
		String sourceGrade,
		GeoScope sourceGeoScope,
		RegionContext sourceRegionContext,
		Double distanceKm,
		Integer vectorRank,
		Integer kgRank,
		BigDecimal relationConfidence,
		VectorKnowledgeRetrievalRequest request
	) {
		Objects.requireNonNull(request, "request must not be null");
		double semantic = rawSemanticScore(
			sourceType,
			sourceGrade,
			vectorRank,
			kgRank,
			relationConfidence
		);
		double geo = request.geoScope() == GeoScope.general
			? NEUTRAL_GEO_SCORE
			: clamp(geoScore(
				sourceGeoScope,
				request.regionContext(),
				sourceRegionContext,
				distanceKm
			));
		double finalScore = finalScore(semantic, geo, request.geoScope());
		return new Scores(round(semantic), round(geo), round(finalScore));
	}

	BigDecimal round(double value) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException("score values must be finite");
		}
		return BigDecimal.valueOf(value).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
	}

	private double rawSemanticScore(
		String sourceType,
		String sourceGrade,
		Integer vectorRank,
		Integer kgRank,
		BigDecimal relationConfidence
	) {
		validateRank(vectorRank, "vectorRank");
		validateRank(kgRank, "kgRank");
		if (vectorRank == null && kgRank == null) {
			throw new IllegalArgumentException("at least one retrieval rank is required");
		}
		double vectorRrf = vectorRank == null ? 0.0d : reciprocalRank(vectorRank);
		double kgRrf = kgRank == null ? 0.0d : reciprocalRank(kgRank);
		double kgWeight = 1.0d - config.vectorWeight();
		double fusionRaw = config.vectorWeight() * vectorRrf + kgWeight * kgRrf;
		double fusionNorm = clamp(fusionRaw * (config.rrfK() + 1.0d));
		double authority = effectiveAuthority(
			sourceType,
			sourceGrade,
			vectorRank,
			kgRank,
			relationConfidence
		);
		return clamp(FUSION_SEMANTIC_WEIGHT * fusionNorm + AUTHORITY_WEIGHT * authority);
	}

	private double reciprocalRank(int rank) {
		return 1.0d / (config.rrfK() + rank);
	}

	/**
	 * Applies the source authority score, gated by knowledge-graph relation confidence when the
	 * evidence is graph-only (no vector-lane corroboration). A KG relation is retrieved by entity
	 * match rather than semantic similarity, so a low-confidence or thin relation must not inherit
	 * the full authority of its curated source. When the same source also surfaces in the vector
	 * lane, cosine similarity already corroborates relevance, so the confidence gate is not applied.
	 * A graph-only relation without a confidence value carries no trust signal, so it fails safe to a
	 * zero authority factor rather than inheriting the full source authority.
	 */
	private double effectiveAuthority(
		String sourceType,
		String sourceGrade,
		Integer vectorRank,
		Integer kgRank,
		BigDecimal relationConfidence
	) {
		double authority = authorityScore(sourceType, sourceGrade);
		boolean graphOnly = vectorRank == null && kgRank != null;
		if (graphOnly) {
			double factor = relationConfidence == null ? 0.0d : confidenceFactor(relationConfidence);
			return authority * factor;
		}
		return authority;
	}

	private double confidenceFactor(BigDecimal relationConfidence) {
		double value = relationConfidence.doubleValue();
		if (!Double.isFinite(value)) {
			return 0.0d;
		}
		return Math.max(0.0d, Math.min(1.0d, value));
	}

	private double authorityScore(String sourceType, String sourceGrade) {
		return switch (sourceType) {
			case "verified_external" -> 0.9d;
			case "accepted_human_answer" -> 0.3d;
			case "curated" -> governmentGrade(sourceGrade) ? 1.0d : 0.8d;
			default -> 0.6d;
		};
	}

	private boolean governmentGrade(String sourceGrade) {
		return "government".equals(sourceGrade) || "public_agency".equals(sourceGrade);
	}

	private double geoScore(
		GeoScope sourceGeoScope,
		RegionContext queryRegionContext,
		RegionContext sourceRegionContext,
		Double distanceKm
	) {
		if (sourceGeoScope == null) {
			return NEUTRAL_GEO_SCORE;
		}
		return switch (sourceGeoScope) {
			case general -> NEUTRAL_GEO_SCORE;
			case regional -> regionalScore(queryRegionContext, sourceRegionContext);
			case local -> distanceDecay(distanceKm, config.localDecayKm());
			case place_specific -> distanceDecay(distanceKm, config.placeSpecificDecayKm());
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

	private double finalScore(double semanticScore, double geoScore, GeoScope queryScope) {
		VectorKnowledgeRetrievalConfig.ScoreWeights weights = config.weightsFor(queryScope);
		return clamp(
			weights.semanticWeight() * semanticScore + weights.geoWeight() * geoScore
		);
	}

	private void validateRank(Integer rank, String field) {
		if (rank != null && rank <= 0) {
			throw new IllegalArgumentException(field + " must be positive");
		}
	}

	private double clamp(double value) {
		if (!Double.isFinite(value)) {
			return 0.0d;
		}
		return Math.max(0.0d, Math.min(1.0d, value));
	}

	record Scores(BigDecimal semanticScore, BigDecimal geoScore, BigDecimal finalScore) {
	}
}
