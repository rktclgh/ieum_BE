package shinhan.fibri.ieum.main.admin.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record AdminKnowledgeCandidateListRequest(
	String status,
	String cursor,
	@Min(1)
	@Max(50)
	Integer size
) {
}
