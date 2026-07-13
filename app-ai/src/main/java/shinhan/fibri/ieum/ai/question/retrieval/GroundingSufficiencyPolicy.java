package shinhan.fibri.ieum.ai.question.retrieval;

import java.util.List;
import java.util.Objects;

public class GroundingSufficiencyPolicy {

	public GroundingSufficiencyResult evaluate(List<VectorKnowledgeEvidence> evidence, boolean highRisk) {
		Objects.requireNonNull(evidence, "evidence must not be null");
		List<VectorKnowledgeEvidence> snapshot = List.copyOf(evidence);
		if (snapshot.isEmpty()) {
			return result(GroundingSufficiencyResult.Reason.EMPTY_EVIDENCE);
		}
		if (!highRisk) {
			return result(GroundingSufficiencyResult.Reason.NON_EMPTY_LOW_RISK_EVIDENCE);
		}

		boolean hasAuthorityEvidence = snapshot.stream()
			.anyMatch(this::authorityEvidence);
		return result(hasAuthorityEvidence
			? GroundingSufficiencyResult.Reason.HIGH_RISK_AUTHORITY_EVIDENCE_PRESENT
			: GroundingSufficiencyResult.Reason.HIGH_RISK_AUTHORITY_EVIDENCE_MISSING);
	}

	private GroundingSufficiencyResult result(GroundingSufficiencyResult.Reason reason) {
		return new GroundingSufficiencyResult(reason.decision(), reason);
	}

	private boolean authorityEvidence(VectorKnowledgeEvidence evidence) {
		boolean approvedSourceType = "curated".equals(evidence.sourceType())
			|| "verified_external".equals(evidence.sourceType());
		return approvedSourceType && ("government".equals(evidence.sourceGrade())
			|| "public_agency".equals(evidence.sourceGrade()));
	}
}
