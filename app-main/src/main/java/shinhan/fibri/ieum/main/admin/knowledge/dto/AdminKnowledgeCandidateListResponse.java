package shinhan.fibri.ieum.main.admin.knowledge.dto;

import java.util.List;

public record AdminKnowledgeCandidateListResponse(
	List<AdminKnowledgeCandidateListItem> items,
	String nextCursor
) {
}
