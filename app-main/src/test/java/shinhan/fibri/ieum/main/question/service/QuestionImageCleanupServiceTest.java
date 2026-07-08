package shinhan.fibri.ieum.main.question.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.file.domain.File;
import shinhan.fibri.ieum.common.file.repository.FileRepository;
import shinhan.fibri.ieum.main.answer.repository.AnswerImageRepository;
import shinhan.fibri.ieum.main.file.storage.FileStorage;
import shinhan.fibri.ieum.main.question.repository.QuestionImageRepository;

class QuestionImageCleanupServiceTest {

	private final QuestionImageRepository questionImageRepository = mock(QuestionImageRepository.class);
	private final AnswerImageRepository answerImageRepository = mock(AnswerImageRepository.class);
	private final UserRepository userRepository = mock(UserRepository.class);
	private final FileRepository fileRepository = mock(FileRepository.class);
	private final FileStorage fileStorage = mock(FileStorage.class);
	private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();
	private final QuestionImageCleanupService service = new QuestionImageCleanupService(
		questionImageRepository,
		answerImageRepository,
		userRepository,
		fileRepository,
		fileStorage,
		new TransactionTemplate(transactionManager)
	);

	@Test
	void cleanupRemovedImagesDeletesS3OnlyAfterDbDeleteTransactionCommits() {
		UUID fileId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		File file = completedFile(fileId);
		when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));
		doAnswer(invocation -> {
			transactionManager.events.add("db-delete");
			return null;
		}).when(fileRepository).delete(file);
		doAnswer(invocation -> {
			transactionManager.events.add("s3-delete:" + invocation.getArgument(0));
			return null;
		}).when(fileStorage).delete(anyString());

		service.cleanRemovedImagesAfterCommit(List.of(fileId));

		assertThat(transactionManager.events).containsExactly(
			"begin",
			"db-delete",
			"commit",
			"s3-delete:final/42/question/11111111-1111-1111-1111-111111111111/original.jpg",
			"s3-delete:final/42/question/11111111-1111-1111-1111-111111111111/display.webp",
			"s3-delete:final/42/question/11111111-1111-1111-1111-111111111111/thumb.webp"
		);
	}

	@Test
	void cleanupRemovedImagesSkipsWhenQuestionStillReferencesFile() {
		UUID fileId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		when(questionImageRepository.existsByFileId(fileId)).thenReturn(true);

		service.cleanRemovedImagesAfterCommit(List.of(fileId));

		verify(fileRepository, never()).findById(fileId);
		verify(fileRepository, never()).delete(any(File.class));
		verify(fileStorage, never()).delete(anyString());
	}

	@Test
	void cleanupRemovedImagesSkipsWhenAnswerStillReferencesFile() {
		UUID fileId = UUID.fromString("33333333-3333-3333-3333-333333333333");
		when(answerImageRepository.existsByFileId(fileId)).thenReturn(true);

		service.cleanRemovedImagesAfterCommit(List.of(fileId));

		verify(fileRepository, never()).findById(fileId);
		verify(fileRepository, never()).delete(any(File.class));
		verify(fileStorage, never()).delete(anyString());
	}

	@Test
	void cleanupRemovedImagesSkipsWhenProfileStillReferencesFile() {
		UUID fileId = UUID.fromString("44444444-4444-4444-4444-444444444444");
		when(userRepository.existsByProfileFileIdAndDeletedAtIsNull(fileId)).thenReturn(true);

		service.cleanRemovedImagesAfterCommit(List.of(fileId));

		verify(fileRepository, never()).findById(fileId);
		verify(fileRepository, never()).delete(any(File.class));
		verify(fileStorage, never()).delete(anyString());
	}

	@Test
	void cleanupRemovedImagesDoesNotDeleteS3WhenDbDeleteFails() {
		UUID fileId = UUID.fromString("55555555-5555-5555-5555-555555555555");
		File file = completedFile(fileId);
		when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));
		doThrow(new IllegalStateException("db unavailable")).when(fileRepository).delete(file);

		assertThatCode(() -> service.cleanRemovedImagesAfterCommit(List.of(fileId))).doesNotThrowAnyException();

		verify(fileStorage, never()).delete(anyString());
	}

	@Test
	void cleanupRemovedImagesKeepsDbDeletedWhenS3DeleteFails() {
		UUID fileId = UUID.fromString("66666666-6666-6666-6666-666666666666");
		File file = completedFile(fileId);
		when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));
		doThrow(new IllegalStateException("s3 unavailable")).when(fileStorage).delete(file.getS3Key());

		assertThatCode(() -> service.cleanRemovedImagesAfterCommit(List.of(fileId))).doesNotThrowAnyException();

		verify(fileRepository).delete(file);
	}

	private File completedFile(UUID fileId) {
		File file = File.pending(fileId, 42L, "final/42/question/" + fileId + "/original.jpg", "image/jpeg", 1024L);
		file.markUploaded(OffsetDateTime.parse("2026-07-07T00:00:00Z"), "image/jpeg", 1024L);
		return file;
	}

	private static class RecordingTransactionManager implements PlatformTransactionManager {

		private final List<String> events = new ArrayList<>();

		@Override
		public TransactionStatus getTransaction(TransactionDefinition definition) {
			events.add("begin");
			return new SimpleTransactionStatus();
		}

		@Override
		public void commit(TransactionStatus status) {
			events.add("commit");
		}

		@Override
		public void rollback(TransactionStatus status) {
			events.add("rollback");
		}
	}
}
