package shinhan.fibri.ieum.ai.question.retrieval;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record HybridKnowledgeEvidence(
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
	Integer vectorRank,
	int kgRank,
	Long relationId,
	String matchedEntity,
	String matchedSide,
	String subject,
	String predicate,
	String object,
	BigDecimal relationConfidence,
	BigDecimal semanticScore,
	BigDecimal geoScore,
	BigDecimal finalScore,
	BigDecimal distanceKm,
	Instant retrievedAt
) implements KnowledgeEvidence {

	public HybridKnowledgeEvidence {
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
		if (cosineSimilarity != null) {
			cosineSimilarity = VectorKnowledgeProvenance.cosineScore(cosineSimilarity);
		}
		if (vectorRank != null && vectorRank <= 0) {
			throw new IllegalArgumentException("vectorRank must be positive");
		}
		if (vectorRank != null && cosineSimilarity == null) {
			throw new IllegalArgumentException("vector-backed evidence must include cosineSimilarity");
		}
		if (kgRank <= 0) {
			throw new IllegalArgumentException("kgRank must be positive");
		}
		if (relationId == null) {
			throw new IllegalArgumentException("relationId must not be null");
		}
		relationId = VectorKnowledgeProvenance.positiveId(relationId, "relationId");
		matchedEntity = VectorKnowledgeProvenance.requiredText(matchedEntity, "matchedEntity");
		matchedSide = matchedSide(matchedSide);
		subject = VectorKnowledgeProvenance.requiredText(subject, "subject");
		predicate = predicate(predicate);
		object = VectorKnowledgeProvenance.requiredText(object, "object");
		relationConfidence = VectorKnowledgeProvenance.unitScore(
			relationConfidence,
			"relationConfidence"
		);
		semanticScore = VectorKnowledgeProvenance.unitScore(semanticScore, "semanticScore");
		geoScore = VectorKnowledgeProvenance.unitScore(geoScore, "geoScore");
		finalScore = VectorKnowledgeProvenance.unitScore(finalScore, "finalScore");
		distanceKm = VectorKnowledgeProvenance.distanceScore(distanceKm);
		retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt must not be null");
	}

	private static String matchedSide(String value) {
		String normalized = VectorKnowledgeProvenance.requiredText(value, "matchedSide");
		if (!"subject".equals(normalized) && !"object".equals(normalized)) {
			throw new IllegalArgumentException("matchedSide must be subject or object");
		}
		return normalized;
	}

	private static String predicate(String value) {
		String normalized = VectorKnowledgeProvenance.requiredText(value, "predicate");
		if (!KnowledgeGraphCandidate.isAllowedPredicate(normalized)) {
			throw new IllegalArgumentException("predicate must belong to the KG v1 allowlist");
		}
		return normalized;
	}
}
