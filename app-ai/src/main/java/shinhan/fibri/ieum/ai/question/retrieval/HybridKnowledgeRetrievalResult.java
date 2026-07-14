package shinhan.fibri.ieum.ai.question.retrieval;

import java.util.List;

public record HybridKnowledgeRetrievalResult(
	String retrievalConfigVersion,
	List<KnowledgeEvidence> candidates,
	List<KnowledgeEvidence> evidence
) {

	public HybridKnowledgeRetrievalResult {
		retrievalConfigVersion = VectorKnowledgeProvenance.requiredText(
			retrievalConfigVersion,
			"retrievalConfigVersion"
		);
		candidates = List.copyOf(candidates);
		evidence = List.copyOf(evidence);
	}
}
