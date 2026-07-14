package shinhan.fibri.ieum.ai.question.retrieval;

import java.math.BigDecimal;
import java.time.Instant;

public interface KnowledgeEvidence {

	long sourceId();

	long chunkId();

	String sourceType();

	String title();

	String excerpt();

	String sourceGrade();

	String contentHash();

	String canonicalUrl();

	String riskDomain();

	String domain();

	GeoScope sourceGeoScope();

	BigDecimal cosineSimilarity();

	BigDecimal semanticScore();

	BigDecimal geoScore();

	BigDecimal finalScore();

	BigDecimal distanceKm();

	Instant retrievedAt();

	default Long relationId() {
		return null;
	}
}
