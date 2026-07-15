package shinhan.fibri.ieum.main.admin.user.repository;

import java.util.List;
import java.util.Optional;

public interface AdminUserHardDeleteRepository {

	Optional<HardDeleteTarget> findForHardDelete(Long userId);

	boolean isReferencedAsActor(Long userId);

	List<String> hardDelete(Long userId);
}
