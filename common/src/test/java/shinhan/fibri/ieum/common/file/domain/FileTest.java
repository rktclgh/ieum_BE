package shinhan.fibri.ieum.common.file.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Persistable;

class FileTest {

	@Test
	void pendingCreatesUnuploadedFileMetadataWithAssignedId() {
		UUID fileId = UUID.fromString("11111111-1111-1111-1111-111111111111");

		File file = File.pending(
				fileId,
				10L,
				"tmp/10/meeting/11111111-1111-1111-1111-111111111111/original.jpg",
				"image/jpeg",
				1024L
		);

		assertThat(file.getFileId()).isEqualTo(fileId);
		assertThat(file.getUploaderId()).isEqualTo(10L);
		assertThat(file.getS3Key()).isEqualTo("tmp/10/meeting/11111111-1111-1111-1111-111111111111/original.jpg");
		assertThat(file.getContentType()).isEqualTo("image/jpeg");
		assertThat(file.getSizeBytes()).isEqualTo(1024L);
		assertThat(file.getUploadedAt()).isNull();
		assertThat(file.getCreatedAt()).isNotNull();
		assertThat(file.isUploaded()).isFalse();
		assertThat(file.isOwnedBy(10L)).isTrue();
		assertThat(file.isOwnedBy(11L)).isFalse();
		assertThat(((Persistable<UUID>) file).isNew()).isTrue();
	}

	@Test
	void promoteKeyAndMarkUploadedFinalizeFileMetadata() {
		File file = File.pending(
				UUID.fromString("22222222-2222-2222-2222-222222222222"),
				20L,
				"tmp/20/profile/22222222-2222-2222-2222-222222222222/original.png",
				"image/png",
				2048L
		);
		OffsetDateTime uploadedAt = OffsetDateTime.parse("2026-07-07T00:00:00Z");

		file.promoteKey("final/20/profile/22222222-2222-2222-2222-222222222222/original.png");
		file.markUploaded(uploadedAt, "image/png", 1900L);

		assertThat(file.getS3Key()).isEqualTo("final/20/profile/22222222-2222-2222-2222-222222222222/original.png");
		assertThat(file.getContentType()).isEqualTo("image/png");
		assertThat(file.getSizeBytes()).isEqualTo(1900L);
		assertThat(file.getUploadedAt()).isEqualTo(uploadedAt);
		assertThat(file.isUploaded()).isTrue();
	}
}
