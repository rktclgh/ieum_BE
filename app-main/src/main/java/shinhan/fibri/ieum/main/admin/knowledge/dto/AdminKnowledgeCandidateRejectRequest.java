package shinhan.fibri.ieum.main.admin.knowledge.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AdminKnowledgeCandidateRejectRequest(
	@Positive
	int version,
	@Size(max = 500)
	String reason
) {
}
