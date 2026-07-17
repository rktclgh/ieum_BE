package shinhan.fibri.ieum.main.admin.knowledge.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import shinhan.fibri.ieum.common.knowledge.KnowledgeRelationPredicate;

public record AdminKnowledgeGraphResponse(
	List<Node> nodes,
	List<Edge> edges,
	boolean truncated
) {

	public record Node(
		String id,
		String label,
		int degree
	) {
	}

	public record Edge(
		Long relationId,
		String source,
		String target,
		KnowledgeRelationPredicate predicate,
		BigDecimal confidence,
		Long sourceId,
		Long evidenceChunkId,
		String sourceDisplayName,
		String evidencePreview,
		OffsetDateTime createdAt
	) {
	}
}
