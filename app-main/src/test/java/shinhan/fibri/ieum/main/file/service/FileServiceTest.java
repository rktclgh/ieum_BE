package shinhan.fibri.ieum.main.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.file.domain.File;
import shinhan.fibri.ieum.common.file.repository.FileRepository;
import shinhan.fibri.ieum.main.file.dto.FileCompleteResponse;
import shinhan.fibri.ieum.main.file.dto.FilePresignRequest;
import shinhan.fibri.ieum.main.file.dto.FilePresignResponse;
import shinhan.fibri.ieum.main.file.dto.FileStreamResponse;
import shinhan.fibri.ieum.main.file.exception.FileNotFoundException;
import shinhan.fibri.ieum.main.file.exception.InvalidFileRequestException;
import shinhan.fibri.ieum.main.file.rendition.FileRendition;
import shinhan.fibri.ieum.main.file.rendition.ImageRenditionGenerator;
import shinhan.fibri.ieum.main.file.storage.FileObjectMetadata;
import shinhan.fibri.ieum.main.file.storage.FileStorage;
import shinhan.fibri.ieum.main.file.storage.StoredFileObject;
import shinhan.fibri.ieum.main.file.storage.StoredFileStream;

class FileServiceTest {

	private final FileRepository fileRepository = mock(FileRepository.class);
	private final FakeFileStorage storage = new FakeFileStorage();
	private final FakeImageRenditionGenerator renditionGenerator = new FakeImageRenditionGenerator();
	private final FileProperties properties = new FileProperties(
			"tmp",
			"final",
			Duration.ofMinutes(15),
			10_485_760L,
			50_000_000L,
			16_384,
			1280,
			320,
			80
	);
	private final FileTransactionalOps transactionalOps = new FileTransactionalOps(fileRepository);
	private final FileService service = new FileService(fileRepository, transactionalOps, storage, renditionGenerator, properties);

	@Test
	void presignCreatesPendingFileAndPutUploadUrl() {
		when(fileRepository.save(any(File.class))).thenAnswer(invocation -> invocation.getArgument(0));

		FilePresignResponse response = service.createPresign(
				principal(),
				new FilePresignRequest("meeting", "image/jpeg", 1024L)
		);

		ArgumentCaptor<File> captor = ArgumentCaptor.forClass(File.class);
		verify(fileRepository).save(captor.capture());
		File saved = captor.getValue();

		assertThat(response.fileId()).isEqualTo(saved.getFileId());
		assertThat(response.uploadUrl()).isEqualTo(URI.create("https://storage.example/" + saved.getS3Key()));
		assertThat(saved.getUploaderId()).isEqualTo(42L);
		assertThat(saved.getS3Key()).isEqualTo("tmp/42/meeting/" + response.fileId() + "/original.jpg");
		assertThat(saved.getContentType()).isEqualTo("image/jpeg");
		assertThat(saved.getSizeBytes()).isEqualTo(1024L);
		assertThat(saved.isUploaded()).isFalse();
		assertThat(storage.presigned).containsExactly(saved.getS3Key());
	}

	@Test
	void presignRejectsUnsupportedContentTypeAndTooLargeFile() {
		assertThatThrownBy(() -> service.createPresign(principal(), new FilePresignRequest("meeting", "image/gif", 1024L)))
			.isInstanceOf(InvalidFileRequestException.class);
		assertThatThrownBy(() -> service.createPresign(principal(), new FilePresignRequest("meeting", "image/png", 10_485_761L)))
			.isInstanceOf(InvalidFileRequestException.class);
		assertThatThrownBy(() -> service.createPresign(principal(), new FilePresignRequest("../meeting", "image/png", 1024L)))
			.isInstanceOf(InvalidFileRequestException.class);
	}

	@Test
	void completePromotesOriginGeneratesRenditionsAndDeletesTmpAfterSavingDbState() {
		UUID fileId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		File file = File.pending(fileId, 42L, "tmp/42/meeting/" + fileId + "/original.jpg", "image/jpeg", 1024L);
		when(fileRepository.findByFileIdAndUploaderId(fileId, 42L)).thenReturn(Optional.of(file));
		when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));
		when(fileRepository.save(any(File.class))).thenAnswer(invocation -> {
			storage.events.add("save");
			return invocation.getArgument(0);
		});
		storage.metadata = new FileObjectMetadata("image/jpeg", 900L);

		FileCompleteResponse response = service.complete(principal(), fileId);

		assertThat(response.fileId()).isEqualTo(fileId);
		assertThat(storage.getKeys).containsExactly("tmp/42/meeting/" + fileId + "/original.jpg");
		assertThat(renditionGenerator.generatedFrom).containsExactly("tmp/42/meeting/" + fileId + "/original.jpg");
		assertThat(storage.putKeys).containsExactly(
				"final/42/meeting/" + fileId + "/original.jpg",
				"final/42/meeting/" + fileId + "/display.webp",
				"final/42/meeting/" + fileId + "/thumb.webp"
		);
		assertThat(storage.deleted).containsExactly("tmp/42/meeting/" + fileId + "/original.jpg");
		assertThat(storage.events).containsSubsequence("put:final/42/meeting/" + fileId + "/thumb.webp", "save", "delete:tmp/42/meeting/" + fileId + "/original.jpg");
		assertThat(file.getS3Key()).isEqualTo("final/42/meeting/" + fileId + "/original.jpg");
		assertThat(file.getContentType()).isEqualTo("image/jpeg");
		assertThat(file.getSizeBytes()).isEqualTo(900L);
		assertThat(file.isUploaded()).isTrue();
		verify(fileRepository).save(file);
	}

	@Test
	void completeKeepsPendingFileAndTmpWhenRenditionGenerationFails() {
		UUID fileId = UUID.fromString("55555555-5555-5555-5555-555555555555");
		File file = File.pending(fileId, 42L, "tmp/42/meeting/" + fileId + "/original.jpg", "image/jpeg", 1024L);
		when(fileRepository.findByFileIdAndUploaderId(fileId, 42L)).thenReturn(Optional.of(file));
		storage.metadata = new FileObjectMetadata("image/jpeg", 900L);
		renditionGenerator.fail = true;

		assertThatThrownBy(() -> service.complete(principal(), fileId))
			.isInstanceOf(IllegalStateException.class);

		assertThat(file.getS3Key()).isEqualTo("tmp/42/meeting/" + fileId + "/original.jpg");
		assertThat(file.isUploaded()).isFalse();
		assertThat(storage.putKeys).isEmpty();
		assertThat(storage.deleted).isEmpty();
		verify(fileRepository, never()).save(file);
	}

	@Test
	void completeDoesNotFailWhenTmpDeleteFailsAfterUploadFinalized() {
		UUID fileId = UUID.fromString("88888888-8888-8888-8888-888888888888");
		File file = File.pending(fileId, 42L, "tmp/42/meeting/" + fileId + "/original.jpg", "image/jpeg", 1024L);
		when(fileRepository.findByFileIdAndUploaderId(fileId, 42L)).thenReturn(Optional.of(file));
		when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));
		when(fileRepository.save(any(File.class))).thenAnswer(invocation -> invocation.getArgument(0));
		storage.metadata = new FileObjectMetadata("image/jpeg", 900L);
		storage.failDelete = true;

		FileCompleteResponse response = service.complete(principal(), fileId);

		assertThat(response.fileId()).isEqualTo(fileId);
		assertThat(file.isUploaded()).isTrue();
		assertThat(storage.deleted).containsExactly("tmp/42/meeting/" + fileId + "/original.jpg");
	}

	@Test
	void completeIsIdempotentWhenFileAlreadyUploaded() {
		UUID fileId = UUID.fromString("33333333-3333-3333-3333-333333333333");
		File file = File.pending(fileId, 42L, "final/42/meeting/" + fileId + "/original.png", "image/png", 2048L);
		file.markUploaded(OffsetDateTime.parse("2026-07-07T00:00:00Z"), "image/png", 2048L);
		when(fileRepository.findByFileIdAndUploaderId(fileId, 42L)).thenReturn(Optional.of(file));

		FileCompleteResponse response = service.complete(principal(), fileId);

		assertThat(response.fileId()).isEqualTo(fileId);
		assertThat(storage.putKeys).isEmpty();
		assertThat(storage.deleted).isEmpty();
		verify(fileRepository, never()).save(any(File.class));
	}

	@Test
	void completeRejectsMissingOrUnownedFile() {
		UUID fileId = UUID.fromString("44444444-4444-4444-4444-444444444444");
		when(fileRepository.findByFileIdAndUploaderId(fileId, 42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.complete(principal(), fileId))
			.isInstanceOf(FileNotFoundException.class);
	}

	@Test
	void streamUsesHeadMetadataAndOpensBodyLazily() throws Exception {
		UUID fileId = UUID.fromString("66666666-6666-6666-6666-666666666666");
		File file = File.pending(fileId, 42L, "final/42/meeting/" + fileId + "/original.png", "image/png", 2048L);
		file.markUploaded(OffsetDateTime.parse("2026-07-07T00:00:00Z"), "image/png", 2048L);
		when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));
		storage.metadata = new FileObjectMetadata("image/webp", 3L);

		FileStreamResponse response = service.stream(principal(), fileId, "thumb");

		assertThat(response.contentType()).isEqualTo("image/webp");
		assertThat(response.contentLength()).isEqualTo(3L);
		assertThat(storage.headKeys).containsExactly("final/42/meeting/" + fileId + "/thumb.webp");
		assertThat(storage.getKeys).isEmpty();

		assertThat(response.body().readAllBytes()).containsExactly(1, 2, 3);
		assertThat(storage.getKeys).containsExactly("final/42/meeting/" + fileId + "/thumb.webp");
	}

	@Test
	void streamRejectsInvalidHeadMetadataBeforeOpeningBody() {
		UUID fileId = UUID.fromString("77777777-7777-7777-7777-777777777777");
		File file = File.pending(fileId, 42L, "final/42/meeting/" + fileId + "/original.png", "image/png", 2048L);
		file.markUploaded(OffsetDateTime.parse("2026-07-07T00:00:00Z"), "image/png", 2048L);
		when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));
		storage.metadata = new FileObjectMetadata(null, 3L);

		assertThatThrownBy(() -> service.stream(principal(), fileId, "thumb"))
			.isInstanceOf(InvalidFileRequestException.class);

		assertThat(storage.getKeys).isEmpty();
	}

	private AuthenticatedUser principal() {
		return new AuthenticatedUser(42L, "user@example.com", UserRole.user, UserStatus.active);
	}

	private static class FakeFileStorage implements FileStorage {

		private final List<String> presigned = new ArrayList<>();
		private final List<String> headKeys = new ArrayList<>();
		private final List<String> getKeys = new ArrayList<>();
		private final List<String> putKeys = new ArrayList<>();
		private final List<String> deleted = new ArrayList<>();
		private final List<String> events = new ArrayList<>();
		private FileObjectMetadata metadata = new FileObjectMetadata("image/jpeg", 1024L);
		private boolean failDelete;

		@Override
		public URI createPresignedPutUrl(String key, String contentType, Duration ttl) {
			presigned.add(key);
			events.add("presign:" + key);
			return URI.create("https://storage.example/" + key);
		}

		@Override
		public URI createPresignedGetUrl(String key, Duration ttl) {
			return URI.create("https://storage.example/" + key);
		}

		@Override
		public FileObjectMetadata head(String key) {
			headKeys.add(key);
			events.add("head:" + key);
			return metadata;
		}

		@Override
		public StoredFileStream get(String key) {
			getKeys.add(key);
			events.add("get:" + key);
			return new StoredFileStream(key, "image/jpeg", 900L, new ByteArrayInputStream(new byte[] {1, 2, 3}));
		}

		@Override
		public void put(String key, String contentType, byte[] bytes) {
			putKeys.add(key);
			events.add("put:" + key);
		}

		@Override
		public void delete(String key) {
			deleted.add(key);
			events.add("delete:" + key);
			if (failDelete) {
				throw new IllegalStateException("delete failed");
			}
		}
	}

	private static class FakeImageRenditionGenerator implements ImageRenditionGenerator {

		private final List<String> generatedFrom = new ArrayList<>();
		private boolean fail;

		@Override
		public List<FileRendition> generate(StoredFileObject origin, FileProperties properties) {
			if (fail) {
				throw new IllegalStateException("rendition failed");
			}
			generatedFrom.add(origin.key());
			return List.of(
					new FileRendition(FileVariant.DISPLAY, "image/webp", new byte[] {4, 5, 6}),
					new FileRendition(FileVariant.THUMB, "image/webp", new byte[] {7, 8, 9})
			);
		}
	}
}
