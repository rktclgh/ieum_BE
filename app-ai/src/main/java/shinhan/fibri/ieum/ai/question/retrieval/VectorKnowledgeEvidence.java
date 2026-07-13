package shinhan.fibri.ieum.ai.question.retrieval;

import java.math.BigDecimal;

public record VectorKnowledgeEvidence(
	long sourceId,
	long chunkId,
	String sourceType,
	String displayName,
	String content,
	String sourceGrade,
	GeoScope sourceGeoScope,
	BigDecimal cosineSimilarity,
	BigDecimal semanticScore,
	BigDecimal geoScore,
	BigDecimal finalScore,
	BigDecimal distanceKm
) {
}
