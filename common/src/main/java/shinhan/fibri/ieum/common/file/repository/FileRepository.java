package shinhan.fibri.ieum.common.file.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import shinhan.fibri.ieum.common.file.domain.File;

public interface FileRepository extends JpaRepository<File, UUID> {

	Optional<File> findByFileIdAndUploaderId(UUID fileId, Long uploaderId);

	List<File> findAllByFileIdInAndUploaderId(List<UUID> fileIds, Long uploaderId);
}
