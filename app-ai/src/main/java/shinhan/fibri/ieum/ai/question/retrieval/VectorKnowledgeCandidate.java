package shinhan.fibri.ieum.ai.question.retrieval;

public record VectorKnowledgeCandidate(
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
	RegionContext sourceRegionContext,
	double cosineSimilarity,
	Double distanceKm
) {

	public VectorKnowledgeCandidate {
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
		sourceRegionContext = sourceRegionContext == null ? RegionContext.empty() : sourceRegionContext;
		cosineSimilarity = VectorKnowledgeProvenance.cosineSimilarity(cosineSimilarity);
		distanceKm = VectorKnowledgeProvenance.distanceKm(distanceKm);
	}
}
