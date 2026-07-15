package shinhan.fibri.ieum.main.admin.user.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.user.exception.AdminUserNotFoundException;
import shinhan.fibri.ieum.main.admin.user.exception.CannotDeleteSelfException;
import shinhan.fibri.ieum.main.admin.user.exception.CannotHardDeleteUserException;
import shinhan.fibri.ieum.main.admin.user.exception.HardDeleteConfirmationMismatchException;
import shinhan.fibri.ieum.main.admin.user.repository.AdminUserHardDeleteRepository;
import shinhan.fibri.ieum.main.admin.user.repository.HardDeleteTarget;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.file.service.S3FileDeletionService;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;

@ExtendWith(OutputCaptureExtension.class)
class AdminUserHardDeleteServiceTest {

	private final AdminUserHardDeleteRepository repository = mock(AdminUserHardDeleteRepository.class);
	private final RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
	private final SseConnectionRegistry sseConnectionRegistry = mock(SseConnectionRegistry.class);
	private final S3FileDeletionService s3FileDeletionService = mock(S3FileDeletionService.class);
	private final ManualExecutor cleanupExecutor = new ManualExecutor();
	private final AdminUserHardDeleteService service = new AdminUserHardDeleteService(
		repository,
		sessionStore,
		sseConnectionRegistry,
		s3FileDeletionService,
		cleanupExecutor
	);

	@AfterEach
	void clearSynchronization() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void hardDeleteThrowsNotFoundForMissingUser() {
		when(repository.findForHardDelete(10L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.hardDelete(adminPrincipal(), 10L, "user@example.com"))
			.isInstanceOf(AdminUserNotFoundException.class);

		verify(repository, never()).hardDelete(10L);
	}

	@Test
	void hardDeleteRejectsDeletingSelf() {
		when(repository.findForHardDelete(1L)).thenReturn(Optional.of(target(1L, "admin@example.com", UserRole.admin)));

		assertThatThrownBy(() -> service.hardDelete(adminPrincipal(), 1L, "admin@example.com"))
			.isInstanceOf(CannotDeleteSelfException.class);

		verify(repository, never()).hardDelete(1L);
	}

	@Test
	void hardDeleteRejectsConfirmationEmailMismatch() {
		when(repository.findForHardDelete(10L)).thenReturn(Optional.of(target(10L, "user@example.com", UserRole.user)));

		assertThatThrownBy(() -> service.hardDelete(adminPrincipal(), 10L, "other@example.com"))
			.isInstanceOf(HardDeleteConfirmationMismatchException.class)
			.hasMessage("confirmationEmail does not match target user email");

		verify(repository, never()).hardDelete(10L);
	}

	@Test
	void hardDeleteNormalizesConfirmationEmailBeforeComparing() {
		when(repository.findForHardDelete(10L)).thenReturn(Optional.of(target(10L, "user@example.com", UserRole.user)));
		when(repository.hardDelete(10L)).thenReturn(List.of());

		service.hardDelete(adminPrincipal(), 10L, "  User@Example.com  ");

		verify(repository).hardDelete(10L);
	}

	@Test
	void hardDeleteRejectsMissingTargetEmailAsConfirmationMismatch() {
		when(repository.findForHardDelete(10L)).thenReturn(Optional.of(target(10L, null, UserRole.user)));

		assertThatThrownBy(() -> service.hardDelete(adminPrincipal(), 10L, "user@example.com"))
			.isInstanceOf(HardDeleteConfirmationMismatchException.class);

		verify(repository, never()).hardDelete(10L);
	}

	@Test
	void hardDeleteRejectsAdminTarget() {
		when(repository.findForHardDelete(10L)).thenReturn(Optional.of(target(10L, "target@example.com", UserRole.admin)));

		assertThatThrownBy(() -> service.hardDelete(adminPrincipal(), 10L, "target@example.com"))
			.isInstanceOf(CannotHardDeleteUserException.class);

		verify(repository, never()).hardDelete(10L);
	}

	@Test
	void hardDeleteRejectsTargetReferencedAsActor() {
		when(repository.findForHardDelete(10L)).thenReturn(Optional.of(target(10L, "user@example.com", UserRole.user)));
		when(repository.isReferencedAsActor(10L)).thenReturn(true);

		assertThatThrownBy(() -> service.hardDelete(adminPrincipal(), 10L, "user@example.com"))
			.isInstanceOf(CannotHardDeleteUserException.class);

		verify(repository, never()).hardDelete(10L);
	}

	@Test
	void hardDeleteRunsSessionAndS3CleanupOnlyAfterCommit() {
		TransactionSynchronizationManager.initSynchronization();
		when(repository.findForHardDelete(10L)).thenReturn(Optional.of(target(10L, "user@example.com", UserRole.user)));
		when(repository.hardDelete(10L)).thenReturn(List.of("final/10/profile/file/original.jpg"));

		service.hardDelete(adminPrincipal(), 10L, "user@example.com");

		verify(sessionStore, never()).revokeAllSessionsOfUser(10L);
		verify(sseConnectionRegistry, never()).closeUser(10L);
		verify(s3FileDeletionService, never()).deleteOriginAndVariantsLogOnly("final/10/profile/file/original.jpg");

		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			synchronization.afterCommit();
		}

		verify(sessionStore, never()).revokeAllSessionsOfUser(10L);
		verify(sseConnectionRegistry, never()).closeUser(10L);
		verify(s3FileDeletionService, never()).deleteOriginAndVariantsLogOnly("final/10/profile/file/original.jpg");

		cleanupExecutor.runAll();

		verify(sessionStore).revokeAllSessionsOfUser(10L);
		verify(sseConnectionRegistry).closeUser(10L);
		verify(s3FileDeletionService).deleteOriginAndVariantsLogOnly("final/10/profile/file/original.jpg");
	}

	@Test
	void hardDeleteLogsAdminActorAndTargetBeforeDeleting(CapturedOutput output) {
		when(repository.findForHardDelete(10L)).thenReturn(Optional.of(target(10L, "user@example.com", UserRole.user)));
		when(repository.hardDelete(10L)).thenReturn(List.of());

		service.hardDelete(adminPrincipal(), 10L, "user@example.com");

		assertThat(output)
			.contains("Admin hard deleting user")
			.contains("adminUserId=1")
			.contains("targetUserId=10");
	}

	@Test
	void hardDeletePassesEachCollectedS3KeyToDeletionService() {
		TransactionSynchronizationManager.initSynchronization();
		when(repository.findForHardDelete(10L)).thenReturn(Optional.of(target(10L, "user@example.com", UserRole.user)));
		when(repository.hardDelete(10L)).thenReturn(List.of(
			"final/10/profile/first/original.jpg",
			"final/10/profile/second/original.jpg"
		));

		service.hardDelete(adminPrincipal(), 10L, "user@example.com");

		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			synchronization.afterCommit();
		}
		cleanupExecutor.runAll();

		verify(s3FileDeletionService).deleteOriginAndVariantsLogOnly("final/10/profile/first/original.jpg");
		verify(s3FileDeletionService).deleteOriginAndVariantsLogOnly("final/10/profile/second/original.jpg");
	}

	private static AuthenticatedUser adminPrincipal() {
		return new AuthenticatedUser(1L, "admin@example.com", UserRole.admin, UserStatus.active);
	}

	private static HardDeleteTarget target(Long userId, String email, UserRole role) {
		return new HardDeleteTarget(userId, email, role);
	}

	private static final class ManualExecutor implements Executor {

		private final List<Runnable> tasks = new ArrayList<>();

		@Override
		public void execute(Runnable command) {
			tasks.add(command);
		}

		private void runAll() {
			List<Runnable> pending = List.copyOf(tasks);
			tasks.clear();
			pending.forEach(Runnable::run);
		}
	}
}
