package shinhan.fibri.ieum.ai.question.retrieval;

import java.util.List;
import java.util.Objects;

public record VectorKnowledgeRetrievalRequest(
	List<Float> embedding,
	GeoScope geoScope,
	GeoPoint coordinates,
	RegionContext regionContext
) {

	public static final int EMBEDDING_DIMENSIONS = 768;

	public VectorKnowledgeRetrievalRequest {
		Objects.requireNonNull(embedding, "embedding must not be null");
		if (embedding.size() != EMBEDDING_DIMENSIONS) {
			throw new IllegalArgumentException("embedding must contain exactly 768 values");
		}
		for (Float value : embedding) {
			if (value == null || !Float.isFinite(value)) {
				throw new IllegalArgumentException("embedding values must be finite");
			}
		}
		embedding = List.copyOf(embedding);
		geoScope = Objects.requireNonNull(geoScope, "geoScope must not be null");
		regionContext = regionContext == null ? RegionContext.empty() : regionContext;
	}
}
