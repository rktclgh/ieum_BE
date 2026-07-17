package shinhan.fibri.ieum.ai.knowledge.relations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface KnowledgeRelationCandidateRepository {

	void enqueue(long sourceId);

	Optional<ClaimedKnowledgeRelationExtractionTask> claimNext(Duration lease, int maxAttempts);

	void completeWithCandidates(
		ClaimedKnowledgeRelationExtractionTask task,
		List<KnowledgeRelationCandidate> candidates,
		String provider,
		String model
	);

	void completeInvalid(ClaimedKnowledgeRelationExtractionTask task, String message);

	void markProviderFailure(
		ClaimedKnowledgeRelationExtractionTask task,
		Duration retryDelay,
		int maxAttempts,
		String message
	);
}
