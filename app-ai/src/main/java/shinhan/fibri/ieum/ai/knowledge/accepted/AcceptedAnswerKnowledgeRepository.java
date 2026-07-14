package shinhan.fibri.ieum.ai.knowledge.accepted;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface AcceptedAnswerKnowledgeRepository {

	Optional<AcceptedAnswerKnowledgeClaim> claimByAnswerId(
		long answerId,
		Duration lease,
		int maxAttempts
	);

	AcceptedAnswerKnowledgeFinalizeResult finalizeClaim(
		AcceptedAnswerKnowledgeClaim claim,
		List<Float> embedding
	);

	boolean markEmbeddingFailure(
		AcceptedAnswerKnowledgeClaim claim,
		Duration retryDelay,
		int maxAttempts
	);
}
