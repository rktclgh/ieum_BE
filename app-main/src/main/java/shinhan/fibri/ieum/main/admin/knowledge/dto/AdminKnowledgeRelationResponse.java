package shinhan.fibri.ieum.main.admin.knowledge.dto;

import java.math.BigDecimal;
import shinhan.fibri.ieum.common.knowledge.KnowledgeRelationPredicate;

public record AdminKnowledgeRelationResponse(
	Long relationId,
	Long sourceId,
	String subject,
	KnowledgeRelationPredicate predicate,
	String object,
	BigDecimal confidence,
	Long evidenceChunkId
) {
}
