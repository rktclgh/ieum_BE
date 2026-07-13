package shinhan.fibri.ieum.ai.question.retrieval;

public record VectorKnowledgeCandidate(
	long sourceId,
	long chunkId,
	String sourceType,
	String displayName,
	String content,
	String sourceGrade,
	GeoScope sourceGeoScope,
	RegionContext sourceRegionContext,
	double cosineSimilarity,
	Double distanceKm
) {

	public VectorKnowledgeCandidate {
		sourceRegionContext = sourceRegionContext == null ? RegionContext.empty() : sourceRegionContext;
	}
}
