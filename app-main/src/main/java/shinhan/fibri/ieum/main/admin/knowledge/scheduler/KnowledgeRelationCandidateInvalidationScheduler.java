package shinhan.fibri.ieum.main.admin.knowledge.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.main.admin.knowledge.repository.JdbcKnowledgeRelationCandidateAdminRepository;

@Component
@RequiredArgsConstructor
public class KnowledgeRelationCandidateInvalidationScheduler {

	private final JdbcKnowledgeRelationCandidateAdminRepository repository;

	@Scheduled(fixedDelay = 300_000)
	@Transactional
	public void invalidateIneligiblePendingCandidates() {
		repository.invalidateIneligiblePendingCandidates();
	}
}
