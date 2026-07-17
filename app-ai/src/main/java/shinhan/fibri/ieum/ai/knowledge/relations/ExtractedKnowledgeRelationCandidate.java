package shinhan.fibri.ieum.ai.knowledge.relations;

public record ExtractedKnowledgeRelationCandidate(
	String subject,
	String predicate,
	String object,
	double confidence,
	String evidence
) {
}
