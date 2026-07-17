package shinhan.fibri.ieum.main.admin.knowledge.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateDetailResponse;
import shinhan.fibri.ieum.main.admin.knowledge.exception.KnowledgeCandidateNotFoundException;
import shinhan.fibri.ieum.main.admin.knowledge.repository.JdbcKnowledgeRelationCandidateAdminRepository;

@Service
@RequiredArgsConstructor
public class KnowledgeRelationCandidateDetailService {

	private final JdbcKnowledgeRelationCandidateAdminRepository repository;

	@Transactional(readOnly = true)
	public AdminKnowledgeCandidateDetailResponse get(long candidateId) {
		var row = repository.findDetail(candidateId)
			.orElseThrow(KnowledgeCandidateNotFoundException::new);
		var relations = repository.findRelationsBySource(row.sourceId())
			.stream()
			.map(KnowledgeRelationCandidateMapper::toRelation)
			.toList();
		return KnowledgeRelationCandidateMapper.toDetail(row, relations);
	}
}
