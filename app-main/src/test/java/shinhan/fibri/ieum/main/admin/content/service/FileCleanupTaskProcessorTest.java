package shinhan.fibri.ieum.main.admin.content.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.admin.content.repository.AdminContentFileCleanupTask;
import shinhan.fibri.ieum.main.admin.content.repository.AdminContentFileCleanupTaskRepository;
import shinhan.fibri.ieum.main.file.service.S3FileDeletionService;

class FileCleanupTaskProcessorTest {

	private final AdminContentFileCleanupTaskRepository repository = mock(AdminContentFileCleanupTaskRepository.class);
	private final S3FileDeletionService s3FileDeletionService = mock(S3FileDeletionService.class);
	private final FileCleanupTaskProperties properties = new FileCleanupTaskProperties(
		"cleanup-worker-test",
		Duration.ofSeconds(30),
		3,
		10
	);
	private final FileCleanupTaskProcessor processor = new FileCleanupTaskProcessor(
		repository,
		s3FileDeletionService,
		properties
	);

	@Test
	void processNextClaimsDeletesStrictlyAndMarksCompleted() {
		AdminContentFileCleanupTask task = task(1L, 1);
		when(repository.claimNext(properties.workerId(), properties.lease(), properties.maxAttempts()))
			.thenReturn(Optional.of(task));
		when(repository.markCompleted(task.taskId(), task.leaseToken())).thenReturn(true);

		processor.processNext();

		verify(s3FileDeletionService).deleteOriginAndVariantsStrict(task.s3Key());
		verify(repository).markCompleted(task.taskId(), task.leaseToken());
		verify(repository, never()).markRetry(eq(task.taskId()), eq(task.leaseToken()), any(), any(), any());
		verify(repository, never()).markDead(eq(task.taskId()), eq(task.leaseToken()), any(), any());
	}

	@Test
	void processNextSchedulesRetryWhenStrictS3DeleteFailsBeforeMaxAttempts() {
		AdminContentFileCleanupTask task = task(2L, 1);
		when(repository.claimNext(properties.workerId(), properties.lease(), properties.maxAttempts()))
			.thenReturn(Optional.of(task));
		when(repository.markRetry(eq(task.taskId()), eq(task.leaseToken()), any(), eq("S3_DELETE_RETRY"), any()))
			.thenReturn(true);
		org.mockito.Mockito.doThrow(new IllegalStateException("s3 unavailable"))
			.when(s3FileDeletionService)
			.deleteOriginAndVariantsStrict(task.s3Key());

		processor.processNext();

		verify(repository).markRetry(eq(task.taskId()), eq(task.leaseToken()), any(), eq("S3_DELETE_RETRY"), any());
		verify(repository, never()).markCompleted(task.taskId(), task.leaseToken());
		verify(repository, never()).markDead(eq(task.taskId()), eq(task.leaseToken()), any(), any());
	}

	@Test
	void processNextMarksDeadWhenStrictS3DeleteFailsAtMaxAttempts() {
		AdminContentFileCleanupTask task = task(3L, 3);
		when(repository.claimNext(properties.workerId(), properties.lease(), properties.maxAttempts()))
			.thenReturn(Optional.of(task));
		when(repository.markDead(eq(task.taskId()), eq(task.leaseToken()), eq("MAX_ATTEMPTS_EXCEEDED"), any()))
			.thenReturn(true);
		org.mockito.Mockito.doThrow(new IllegalStateException("s3 unavailable"))
			.when(s3FileDeletionService)
			.deleteOriginAndVariantsStrict(task.s3Key());

		processor.processNext();

		verify(repository).markDead(eq(task.taskId()), eq(task.leaseToken()), eq("MAX_ATTEMPTS_EXCEEDED"), any());
		verify(repository, never()).markRetry(eq(task.taskId()), eq(task.leaseToken()), any(), any(), any());
	}

	@Test
	void processNextDoesNotRetryWhenCompletionFenceRejectsStaleLease() {
		AdminContentFileCleanupTask task = task(4L, 1);
		when(repository.claimNext(properties.workerId(), properties.lease(), properties.maxAttempts()))
			.thenReturn(Optional.of(task));
		when(repository.markCompleted(task.taskId(), task.leaseToken())).thenReturn(false);

		processor.processNext();

		verify(s3FileDeletionService).deleteOriginAndVariantsStrict(task.s3Key());
		verify(repository).markCompleted(task.taskId(), task.leaseToken());
		verify(repository, never()).markRetry(eq(task.taskId()), eq(task.leaseToken()), any(), any(), any());
		verify(repository, never()).markDead(eq(task.taskId()), eq(task.leaseToken()), any(), any());
	}

	private static AdminContentFileCleanupTask task(long taskId, int attempts) {
		return new AdminContentFileCleanupTask(
			taskId,
			"final/admin/content/" + taskId + "/original.jpg",
			OffsetDateTime.parse("2026-07-24T00:00:30Z"),
			UUID.randomUUID(),
			attempts
		);
	}
}
