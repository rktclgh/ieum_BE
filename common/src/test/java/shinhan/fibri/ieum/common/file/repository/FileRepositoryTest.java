package shinhan.fibri.ieum.common.file.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.domain.Persistable;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.file.domain.File;

@DataJpaTest
class FileRepositoryTest {

	@Autowired
	private FileRepository fileRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void savesAssignedUuidWithoutLosingNewEntityStateAfterReload() {
		UUID fileId = UUID.fromString("33333333-3333-3333-3333-333333333333");
		File saved = fileRepository.saveAndFlush(File.pending(
				fileId,
				30L,
				"tmp/30/meeting/33333333-3333-3333-3333-333333333333/original.jpg",
				"image/jpeg",
				4096L
		));

		assertThat(saved.getFileId()).isEqualTo(fileId);
		assertThat(((Persistable<UUID>) saved).isNew()).isFalse();

		entityManager.clear();

		File reloaded = fileRepository.findById(fileId).orElseThrow();
		assertThat(reloaded.getS3Key()).isEqualTo("tmp/30/meeting/33333333-3333-3333-3333-333333333333/original.jpg");
		assertThat(((Persistable<UUID>) reloaded).isNew()).isFalse();
	}

	@Test
	void findByFileIdAndUploaderIdReturnsOnlyOwnedFile() {
		UUID fileId = UUID.fromString("44444444-4444-4444-4444-444444444444");
		File file = fileRepository.saveAndFlush(File.pending(
				fileId,
				40L,
				"tmp/40/profile/44444444-4444-4444-4444-444444444444/original.png",
				"image/png",
				8192L
		));

		assertThat(fileRepository.findByFileIdAndUploaderId(fileId, 40L)).contains(file);
		assertThat(fileRepository.findByFileIdAndUploaderId(fileId, 41L)).isEmpty();
	}

	@SpringBootApplication(scanBasePackages = "shinhan.fibri.ieum.common")
	@EntityScan(basePackageClasses = {User.class, File.class})
	static class TestApplication {
	}
}
