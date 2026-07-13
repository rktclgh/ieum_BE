package shinhan.fibri.ieum.ai.question.retrieval;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record VectorKnowledgeRetrievalConfig(
	String retrievalConfigVersion,
	int globalOverfetch,
	int geoOverfetch,
	int laneCandidateLimit,
	int evidenceLimit,
	int rrfK,
	double vectorWeight,
	double localDecayKm,
	double placeSpecificDecayKm,
	Map<GeoScope, ScoreWeights> scopeWeights
) {

	public static final String DEFAULT_VERSION = "retrieval-v1";
	public static final int DEFAULT_GLOBAL_OVERFETCH = 100;
	public static final int DEFAULT_GEO_OVERFETCH = 100;
	public static final int DEFAULT_CANDIDATE_LIMIT = 20;
	public static final int DEFAULT_EVIDENCE_LIMIT = 8;
	public static final int DEFAULT_RRF_K = 60;
	public static final double DEFAULT_VECTOR_WEIGHT = 0.6d;
	public static final double DEFAULT_LOCAL_DECAY_KM = 10.0d;
	public static final double DEFAULT_PLACE_SPECIFIC_DECAY_KM = 2.0d;

	public VectorKnowledgeRetrievalConfig {
		retrievalConfigVersion = requireText(retrievalConfigVersion, "retrievalConfigVersion");
		if (globalOverfetch <= 0 || geoOverfetch <= 0) {
			throw new IllegalArgumentException("overfetch limits must be positive");
		}
		if (laneCandidateLimit <= 0
			|| laneCandidateLimit > globalOverfetch
			|| laneCandidateLimit > geoOverfetch) {
			throw new IllegalArgumentException("laneCandidateLimit must be positive and bounded by each overfetch");
		}
		if (evidenceLimit <= 0 || evidenceLimit > laneCandidateLimit * 2) {
			throw new IllegalArgumentException("evidenceLimit must be positive and bounded by the lane union");
		}
		if (rrfK <= 0) {
			throw new IllegalArgumentException("rrfK must be positive");
		}
		if (!unitInterval(vectorWeight)) {
			throw new IllegalArgumentException("vectorWeight must be between 0 and 1");
		}
		if (!positiveFinite(localDecayKm) || !positiveFinite(placeSpecificDecayKm)) {
			throw new IllegalArgumentException("distance decay scales must be positive and finite");
		}
		Objects.requireNonNull(scopeWeights, "scopeWeights must not be null");
		EnumMap<GeoScope, ScoreWeights> copy = new EnumMap<>(GeoScope.class);
		copy.putAll(scopeWeights);
		for (GeoScope scope : GeoScope.values()) {
			if (!copy.containsKey(scope)) {
				throw new IllegalArgumentException("scopeWeights must contain " + scope);
			}
		}
		scopeWeights = Map.copyOf(copy);
	}

	public static VectorKnowledgeRetrievalConfig defaults() {
		return new VectorKnowledgeRetrievalConfig(
			DEFAULT_VERSION,
			DEFAULT_GLOBAL_OVERFETCH,
			DEFAULT_GEO_OVERFETCH,
			DEFAULT_CANDIDATE_LIMIT,
			DEFAULT_EVIDENCE_LIMIT,
			DEFAULT_RRF_K,
			DEFAULT_VECTOR_WEIGHT,
			DEFAULT_LOCAL_DECAY_KM,
			DEFAULT_PLACE_SPECIFIC_DECAY_KM,
			Map.of(
				GeoScope.general, new ScoreWeights(0.95d, 0.05d),
				GeoScope.regional, new ScoreWeights(0.80d, 0.20d),
				GeoScope.local, new ScoreWeights(0.70d, 0.30d),
				GeoScope.place_specific, new ScoreWeights(0.60d, 0.40d)
			)
		);
	}

	public ScoreWeights weightsFor(GeoScope scope) {
		return scopeWeights.get(Objects.requireNonNull(scope, "scope must not be null"));
	}

	private static boolean unitInterval(double value) {
		return Double.isFinite(value) && value >= 0.0d && value <= 1.0d;
	}

	private static boolean positiveFinite(double value) {
		return Double.isFinite(value) && value > 0.0d;
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.trim();
	}

	public record ScoreWeights(double semanticWeight, double geoWeight) {

		public ScoreWeights {
			if (!unitInterval(semanticWeight) || !unitInterval(geoWeight)) {
				throw new IllegalArgumentException("score weights must be between 0 and 1");
			}
			if (Math.abs(semanticWeight + geoWeight - 1.0d) > 0.0000001d) {
				throw new IllegalArgumentException("semanticWeight and geoWeight must sum to 1");
			}
		}
	}
}
