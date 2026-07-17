package shinhan.fibri.ieum.main.admin.knowledge.dto;

public record AdminKnowledgeCandidateDecisionResponse(
	Long candidateId,
	String status,
	int version,
	AdminKnowledgeRelationResponse relation
) {
}
