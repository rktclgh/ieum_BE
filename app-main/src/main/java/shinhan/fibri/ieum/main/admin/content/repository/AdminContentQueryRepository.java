package shinhan.fibri.ieum.main.admin.content.repository;

import java.util.List;
import java.util.Optional;
import shinhan.fibri.ieum.main.admin.content.domain.AdminContentType;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentDetailResponse;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentListItem;

public interface AdminContentQueryRepository {

	List<AdminContentListItem> findQuestions(Long cursorId, int limit);

	List<AdminContentListItem> findMeetings(Long cursorId, int limit);

	Optional<AdminContentDetailResponse> findDetail(AdminContentType type, Long id);

	Optional<AdminContentDetailResponse> lockDetail(AdminContentType type, Long id);

	void update(AdminContentType type, Long id, String title, String content);
}
