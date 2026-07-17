package shinhan.fibri.ieum.main.admin.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import shinhan.fibri.ieum.common.knowledge.KnowledgeRelationPredicate;

public record AdminKnowledgeCandidateApproveRequest(
	@NotNull
	@Positive
	Integer version,
	@NotBlank
	@Size(max = 200)
	String subject,
	@NotNull
	KnowledgeRelationPredicate predicate,
	@NotBlank
	@Size(max = 200)
	String object
) {
}
