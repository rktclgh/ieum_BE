package shinhan.fibri.ieum.ai.question.retrieval;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface KnowledgeGraphRepository {

	List<KnowledgeGraphCandidate> findOneHopCandidates(
		List<String> canonicalEntityCandidates,
		int limit
	);

	List<KnowledgeGraphCandidate> findOneHopCandidates(
		List<String> canonicalEntityCandidates,
		GeoPoint coordinates,
		int limit
	);

	Set<Long> findEligibleRelationIds(Collection<Long> relationIds);
}
