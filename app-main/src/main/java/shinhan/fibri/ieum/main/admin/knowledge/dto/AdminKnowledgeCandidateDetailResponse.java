package shinhan.fibri.ieum.main.admin.knowledge.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import shinhan.fibri.ieum.common.knowledge.KnowledgeRelationPredicate;

public record AdminKnowledgeCandidateDetailResponse(
	Long candidateId,
	Long sourceId,
	Long evidenceChunkId,
	String subject,
	KnowledgeRelationPredicate predicate,
	String object,
	BigDecimal confidence,
	String evidenceExcerpt,
	String extractionProvider,
	String extractionModel,
	String status,
	int version,
	Long reviewerUserId,
	OffsetDateTime reviewedAt,
	String reviewNote,
	Long promotionRelationId,
	OffsetDateTime createdAt,
	OffsetDateTime updatedAt,
	AdminKnowledgeCandidateSourceDetail source,
	List<AdminKnowledgeRelationResponse> sameSourceRelations
) {
}
