package shinhan.fibri.ieum.ai.question.retrieval;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record VectorKnowledgeEvidence(
	long sourceId,
	long chunkId,
	String sourceType,
	String title,
	String excerpt,
	String sourceGrade,
	String contentHash,
	String canonicalUrl,
	String riskDomain,
	String domain,
	GeoScope sourceGeoScope,
	BigDecimal cosineSimilarity,
	BigDecimal semanticScore,
	BigDecimal geoScore,
	BigDecimal finalScore,
	BigDecimal distanceKm,
	Instant retrievedAt
) implements KnowledgeEvidence {

	public VectorKnowledgeEvidence {
		sourceId = VectorKnowledgeProvenance.positiveId(sourceId, "sourceId");
		chunkId = VectorKnowledgeProvenance.positiveId(chunkId, "chunkId");
		sourceType = VectorKnowledgeProvenance.requiredText(sourceType, "sourceType");
		title = VectorKnowledgeProvenance.requiredText(title, "title");
		excerpt = VectorKnowledgeProvenance.requiredText(excerpt, "excerpt");
		sourceGrade = VectorKnowledgeProvenance.normalizedSourceGrade(sourceGrade);
		contentHash = VectorKnowledgeProvenance.contentHash(contentHash);
		canonicalUrl = VectorKnowledgeProvenance.canonicalUrl(canonicalUrl);
		riskDomain = VectorKnowledgeProvenance.optionalText(riskDomain);
		domain = VectorKnowledgeProvenance.optionalText(domain);
		cosineSimilarity = VectorKnowledgeProvenance.cosineScore(cosineSimilarity);
		semanticScore = VectorKnowledgeProvenance.unitScore(semanticScore, "semanticScore");
		geoScore = VectorKnowledgeProvenance.unitScore(geoScore, "geoScore");
		finalScore = VectorKnowledgeProvenance.unitScore(finalScore, "finalScore");
		distanceKm = VectorKnowledgeProvenance.distanceScore(distanceKm);
		retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt must not be null");
	}
}
