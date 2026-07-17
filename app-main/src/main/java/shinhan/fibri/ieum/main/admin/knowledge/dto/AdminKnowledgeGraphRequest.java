package shinhan.fibri.ieum.main.admin.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import shinhan.fibri.ieum.common.knowledge.KnowledgeRelationPredicate;

public record AdminKnowledgeGraphRequest(
	@Size(max = 100)
	String query,
	@Size(max = 200)
	String focus,
	KnowledgeRelationPredicate predicate,
	@Min(1)
	@Max(80)
	Integer limit
) {

	private static final int DEFAULT_LIMIT = 60;

	public AdminKnowledgeGraphRequest {
		query = normalize(query);
		focus = normalize(focus);
		limit = limit == null ? DEFAULT_LIMIT : limit;
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}
}
