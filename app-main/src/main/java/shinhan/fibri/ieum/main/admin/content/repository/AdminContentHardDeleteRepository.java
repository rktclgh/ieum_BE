package shinhan.fibri.ieum.main.admin.content.repository;

import java.util.Optional;
import shinhan.fibri.ieum.main.admin.content.domain.AdminContentType;

public interface AdminContentHardDeleteRepository {

	Optional<AdminContentHardDeleteTarget> preview(AdminContentType type, Long id);

	Optional<AdminContentHardDeleteTarget> findForHardDelete(AdminContentType type, Long id);

	AdminContentHardDeleteResult hardDelete(AdminContentType type, AdminContentHardDeleteTarget target);
}
