package shinhan.fibri.ieum.main.admin.knowledge.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateListItem;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateListRequest;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeCandidateListResponse;
import shinhan.fibri.ieum.main.admin.knowledge.repository.JdbcKnowledgeRelationCandidateAdminRepository;

@Service
@RequiredArgsConstructor
public class KnowledgeRelationCandidateQueryService {

	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 50;

	private final JdbcKnowledgeRelationCandidateAdminRepository repository;

	@Transactional(readOnly = true)
	public AdminKnowledgeCandidateListResponse list(AdminKnowledgeCandidateListRequest request) {
		int size = normalizeSize(request.size());
		String status = KnowledgeCandidateStatus.normalize(request.status());
		Long cursorId = KnowledgeCandidateCursor.decode(request.cursor());
		var rows = repository.findCandidates(status, cursorId, size + 1);
		boolean hasNext = rows.size() > size;
		List<AdminKnowledgeCandidateListItem> items = rows.stream()
			.limit(size)
			.map(KnowledgeRelationCandidateMapper::toListItem)
			.toList();
		String nextCursor = hasNext ? KnowledgeCandidateCursor.encode(items.getLast().candidateId()) : null;
		return new AdminKnowledgeCandidateListResponse(items, nextCursor);
	}

	private int normalizeSize(Integer requested) {
		if (requested == null) {
			return DEFAULT_SIZE;
		}
		if (requested < 1 || requested > MAX_SIZE) {
			throw new IllegalArgumentException("Knowledge candidate page size must be between 1 and 50");
		}
		return requested;
	}
}
