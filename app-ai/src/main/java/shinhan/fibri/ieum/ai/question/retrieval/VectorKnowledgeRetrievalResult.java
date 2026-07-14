package shinhan.fibri.ieum.ai.question.retrieval;

import java.util.List;
import java.util.Objects;

public record VectorKnowledgeRetrievalResult(
	String retrievalConfigVersion,
	List<VectorKnowledgeEvidence> candidates,
	List<VectorKnowledgeEvidence> evidence
) {

	public VectorKnowledgeRetrievalResult {
		retrievalConfigVersion = Objects.requireNonNull(
			retrievalConfigVersion,
			"retrievalConfigVersion must not be null"
		);
		candidates = List.copyOf(candidates);
		evidence = List.copyOf(evidence);
	}
}
