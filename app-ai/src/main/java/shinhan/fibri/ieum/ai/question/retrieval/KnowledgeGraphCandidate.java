package shinhan.fibri.ieum.ai.question.retrieval;

import java.math.BigDecimal;
import java.util.Set;

public record KnowledgeGraphCandidate(
	String matchedEntity,
	String matchedSide,
	long relationId,
	long sourceId,
	long chunkId,
	String subject,
	String predicate,
	String object,
	BigDecimal relationConfidence,
	String sourceType,
	String title,
	String excerpt,
	String sourceGrade,
	String contentHash,
	String canonicalUrl,
	String riskDomain,
	GeoScope sourceGeoScope,
	RegionContext sourceRegionContext,
	Double distanceKm
) {

	static final Set<String> ALLOWED_PREDICATES = Set.of(
		"requires",
		"applies_to",
		"located_in",
		"exception_of",
		"prevents",
		"supports",
		"has_deadline",
		"depends_on",
		"reported_to",
		"used_for"
	);

	public KnowledgeGraphCandidate {
		matchedEntity = VectorKnowledgeProvenance.requiredText(matchedEntity, "matchedEntity");
		matchedSide = matchedSide(matchedSide);
		relationId = VectorKnowledgeProvenance.positiveId(relationId, "relationId");
		sourceId = VectorKnowledgeProvenance.positiveId(sourceId, "sourceId");
		chunkId = VectorKnowledgeProvenance.positiveId(chunkId, "chunkId");
		subject = VectorKnowledgeProvenance.requiredText(subject, "subject");
		predicate = predicate(predicate);
		object = VectorKnowledgeProvenance.requiredText(object, "object");
		relationConfidence = VectorKnowledgeProvenance.unitScore(
			relationConfidence,
			"relationConfidence"
		);
		sourceType = VectorKnowledgeProvenance.requiredText(sourceType, "sourceType");
		title = VectorKnowledgeProvenance.requiredText(title, "title");
		excerpt = VectorKnowledgeProvenance.requiredText(excerpt, "excerpt");
		sourceGrade = VectorKnowledgeProvenance.normalizedSourceGrade(sourceGrade);
		contentHash = VectorKnowledgeProvenance.contentHash(contentHash);
		canonicalUrl = VectorKnowledgeProvenance.canonicalUrl(canonicalUrl);
		riskDomain = VectorKnowledgeProvenance.optionalText(riskDomain);
		sourceRegionContext = sourceRegionContext == null ? RegionContext.empty() : sourceRegionContext;
		distanceKm = VectorKnowledgeProvenance.distanceKm(distanceKm);
	}

	static boolean isAllowedPredicate(String value) {
		return ALLOWED_PREDICATES.contains(value);
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
		if (!isAllowedPredicate(normalized)) {
			throw new IllegalArgumentException("predicate must belong to the KG v1 allowlist");
		}
		return normalized;
	}
}
