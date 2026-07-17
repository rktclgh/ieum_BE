package shinhan.fibri.ieum.main.admin.knowledge.service;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.main.admin.audit.domain.AdminAuditAction;
import shinhan.fibri.ieum.main.admin.audit.repository.AdminAuditLogWriter;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateApproveRequest;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateDecisionResponse;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateRejectRequest;
import shinhan.fibri.ieum.main.admin.knowledge.exception.KnowledgeCandidateConcurrentlyChangedException;
import shinhan.fibri.ieum.main.admin.knowledge.exception.KnowledgeCandidateNotFoundException;
import shinhan.fibri.ieum.main.admin.knowledge.exception.KnowledgeCandidateSourceIneligibleException;
import shinhan.fibri.ieum.main.admin.knowledge.repository.JdbcKnowledgeRelationCandidateAdminRepository;
import shinhan.fibri.ieum.main.admin.knowledge.repository.JdbcKnowledgeRelationCandidateAdminRepository.KnowledgeCandidateRow;
import shinhan.fibri.ieum.main.admin.knowledge.repository.JdbcKnowledgeRelationCandidateAdminRepository.RelationRow;

@Service
@RequiredArgsConstructor
public class KnowledgeRelationCandidateDecisionService {

	private final JdbcKnowledgeRelationCandidateAdminRepository repository;
	private final AdminAuditLogWriter auditLogWriter;

	@Transactional(noRollbackFor = KnowledgeCandidateSourceIneligibleException.class)
	public AdminKnowledgeCandidateDecisionResponse approve(
		long candidateId,
		long actorUserId,
		AdminKnowledgeCandidateApproveRequest request
	) {
		KnowledgeCandidateRow candidate = lockPendingCandidate(candidateId, request.version());
		if (!repository.sourceEligible(candidate.sourceId())) {
			repository.invalidatePendingCandidate(candidateId);
			throw new KnowledgeCandidateSourceIneligibleException();
		}
		RelationRow relation = repository.upsertRelation(
			candidate.sourceId(),
			candidate.evidenceChunkId(),
			requiredText(request.subject(), "subject"),
			request.predicate(),
			requiredText(request.object(), "object"),
			candidate.confidence()
		);
		KnowledgeCandidateRow approved = repository.approveCandidate(candidateId, actorUserId, relation);
		auditLogWriter.append(
			actorUserId,
			AdminAuditAction.KNOWLEDGE_RELATION_APPROVED,
			"knowledge_relation_candidate",
			candidateId,
			Map.of(
				"sourceId", approved.sourceId(),
				"relationId", relation.relationId(),
				"previousStatus", candidate.status(),
				"newStatus", approved.status(),
				"version", approved.version()
			)
		);
		return new AdminKnowledgeCandidateDecisionResponse(
			approved.candidateId(),
			approved.status(),
			approved.version(),
			KnowledgeRelationCandidateMapper.toRelation(relation)
		);
	}

	@Transactional
	public AdminKnowledgeCandidateDecisionResponse reject(
		long candidateId,
		long actorUserId,
		AdminKnowledgeCandidateRejectRequest request
	) {
		KnowledgeCandidateRow candidate = lockPendingCandidate(candidateId, request.version());
		String reason = normalizeReason(request.reason());
		KnowledgeCandidateRow rejected = repository.rejectCandidate(candidateId, actorUserId, reason);
		auditLogWriter.append(
			actorUserId,
			AdminAuditAction.KNOWLEDGE_RELATION_REJECTED,
			"knowledge_relation_candidate",
			candidateId,
			Map.of(
				"sourceId", rejected.sourceId(),
				"previousStatus", candidate.status(),
				"newStatus", rejected.status(),
				"version", rejected.version(),
				"reason", reason
			)
		);
		return new AdminKnowledgeCandidateDecisionResponse(
			rejected.candidateId(),
			rejected.status(),
			rejected.version(),
			null
		);
	}

	private KnowledgeCandidateRow lockPendingCandidate(long candidateId, int requestVersion) {
		KnowledgeCandidateRow candidate = repository.findCandidateForUpdate(candidateId)
			.orElseThrow(KnowledgeCandidateNotFoundException::new);
		if (candidate.version() != requestVersion || !"pending".equals(candidate.status())) {
			throw new KnowledgeCandidateConcurrentlyChangedException();
		}
		return candidate;
	}

	private String requiredText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.trim();
	}

	private String normalizeReason(String reason) {
		if (reason == null || reason.isBlank()) {
			return "";
		}
		String trimmed = reason.trim();
		return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
	}
}
