package shinhan.fibri.ieum.main.admin.knowledge.service;

import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateDetailResponse;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateListItem;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateSourceDetail;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateSourceSummary;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeRelationResponse;
import shinhan.fibri.ieum.main.admin.knowledge.repository.JdbcKnowledgeRelationCandidateAdminRepository.KnowledgeCandidateDetailRow;
import shinhan.fibri.ieum.main.admin.knowledge.repository.JdbcKnowledgeRelationCandidateAdminRepository.KnowledgeCandidateListRow;
import shinhan.fibri.ieum.main.admin.knowledge.repository.JdbcKnowledgeRelationCandidateAdminRepository.RelationRow;

final class KnowledgeRelationCandidateMapper {

	private KnowledgeRelationCandidateMapper() {
	}

	static AdminKnowledgeRelationResponse toRelation(RelationRow row) {
		return new AdminKnowledgeRelationResponse(
			row.relationId(),
			row.sourceId(),
			row.subject(),
			row.predicate(),
			row.object(),
			row.confidence(),
			row.evidenceChunkId()
		);
	}

	static AdminKnowledgeCandidateListItem toListItem(KnowledgeCandidateListRow row) {
		return new AdminKnowledgeCandidateListItem(
			row.candidateId(),
			row.sourceId(),
			row.evidenceChunkId(),
			row.subject(),
			row.predicate(),
			row.object(),
			row.confidence(),
			row.evidenceExcerpt(),
			row.status(),
			row.version(),
			row.reviewerUserId(),
			row.reviewedAt(),
			row.reviewNote(),
			row.promotionRelationId(),
			row.createdAt(),
			row.updatedAt(),
			new AdminKnowledgeCandidateSourceSummary(
				row.questionId(),
				row.answerId(),
				row.sourceDisplayName(),
				row.sourceStatus(),
				row.sourceActive(),
				row.validUntil(),
				row.sourceEligible()
			)
		);
	}

	static AdminKnowledgeCandidateDetailResponse toDetail(
		KnowledgeCandidateDetailRow row,
		java.util.List<AdminKnowledgeRelationResponse> sameSourceRelations
	) {
		return new AdminKnowledgeCandidateDetailResponse(
			row.candidateId(),
			row.sourceId(),
			row.evidenceChunkId(),
			row.subject(),
			row.predicate(),
			row.object(),
			row.confidence(),
			row.evidenceExcerpt(),
			row.extractionProvider(),
			row.extractionModel(),
			row.status(),
			row.version(),
			row.reviewerUserId(),
			row.reviewedAt(),
			row.reviewNote(),
			row.promotionRelationId(),
			row.createdAt(),
			row.updatedAt(),
			new AdminKnowledgeCandidateSourceDetail(
				row.questionId(),
				row.answerId(),
				row.sourceDisplayName(),
				row.sourceStatus(),
				row.sourceActive(),
				row.validUntil(),
				row.sourceEligible(),
				row.questionTitle(),
				row.questionContent(),
				row.answerContent(),
				row.chunkContent()
			),
			sameSourceRelations
		);
	}
}
