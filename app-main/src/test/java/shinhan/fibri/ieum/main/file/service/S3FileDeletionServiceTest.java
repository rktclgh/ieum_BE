package shinhan.fibri.ieum.main.file.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.file.storage.FileStorage;

class S3FileDeletionServiceTest {

	private final FileStorage fileStorage = mock(FileStorage.class);
	private final S3FileDeletionService service = new S3FileDeletionService(fileStorage);

	@Test
	void deleteOriginAndVariantsDeletesOriginDisplayAndThumb() {
		service.deleteOriginAndVariantsLogOnly("final/42/question/file/original.jpg");

		verify(fileStorage).delete("final/42/question/file/original.jpg");
		verify(fileStorage).delete("final/42/question/file/display.webp");
		verify(fileStorage).delete("final/42/question/file/thumb.webp");
	}

	@Test
	void deleteOriginAndVariantsKeepsFailuresLogOnly() {
		doThrow(new IllegalStateException("s3 unavailable"))
			.when(fileStorage).delete("final/42/question/file/original.jpg");

		assertThatCode(() -> service.deleteOriginAndVariantsLogOnly("final/42/question/file/original.jpg"))
			.doesNotThrowAnyException();

		verify(fileStorage).delete("final/42/question/file/display.webp");
		verify(fileStorage).delete("final/42/question/file/thumb.webp");
	}

	@Test
	void malformedOriginKeyDoesNotStopOriginDeletion() {
		assertThatCode(() -> service.deleteOriginAndVariantsLogOnly("malformed-key"))
			.doesNotThrowAnyException();

		verify(fileStorage).delete("malformed-key");
	}
}
