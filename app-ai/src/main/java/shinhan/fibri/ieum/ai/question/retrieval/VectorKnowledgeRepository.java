package shinhan.fibri.ieum.ai.question.retrieval;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface VectorKnowledgeRepository {

	List<VectorKnowledgeCandidate> findGlobalCandidates(List<Float> embedding, int limit);

	List<VectorKnowledgeCandidate> findLocationAwareCandidates(
		List<Float> embedding,
		RegionContext regionContext,
		GeoPoint coordinates,
		int limit
	);

	Set<Long> findEligibleChunkIds(Collection<Long> chunkIds);
}
