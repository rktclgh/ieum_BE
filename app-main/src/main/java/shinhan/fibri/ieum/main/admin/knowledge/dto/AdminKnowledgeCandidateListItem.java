package shinhan.fibri.ieum.main.admin.knowledge.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import shinhan.fibri.ieum.common.knowledge.KnowledgeRelationPredicate;

public record AdminKnowledgeCandidateListItem(
	Long candidateId,
	Long sourceId,
	Long evidenceChunkId,
	String subject,
	KnowledgeRelationPredicate predicate,
	String object,
	BigDecimal confidence,
	String evidenceExcerpt,
	String status,
	int version,
	Long reviewerUserId,
	OffsetDateTime reviewedAt,
	String reviewNote,
	Long promotionRelationId,
	OffsetDateTime createdAt,
	OffsetDateTime updatedAt,
	AdminKnowledgeCandidateSourceSummary source
) {
}
