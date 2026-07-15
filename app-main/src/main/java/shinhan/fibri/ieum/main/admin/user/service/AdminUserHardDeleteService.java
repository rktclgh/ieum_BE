package shinhan.fibri.ieum.main.admin.user.service;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.validation.AuthEmailNormalizer;
import shinhan.fibri.ieum.main.admin.user.exception.AdminUserNotFoundException;
import shinhan.fibri.ieum.main.admin.user.exception.CannotDeleteSelfException;
import shinhan.fibri.ieum.main.admin.user.exception.CannotHardDeleteUserException;
import shinhan.fibri.ieum.main.admin.user.exception.HardDeleteConfirmationMismatchException;
import shinhan.fibri.ieum.main.admin.user.repository.AdminUserHardDeleteRepository;
import shinhan.fibri.ieum.main.admin.user.repository.HardDeleteTarget;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.file.service.S3FileDeletionService;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;

@Service
public class AdminUserHardDeleteService {

	private static final Logger log = LoggerFactory.getLogger(AdminUserHardDeleteService.class);

	private final AdminUserHardDeleteRepository repository;
	private final RedisAuthSessionStore sessionStore;
	private final SseConnectionRegistry sseConnectionRegistry;
	private final S3FileDeletionService s3FileDeletionService;
	private final Executor cleanupExecutor;

	public AdminUserHardDeleteService(
		AdminUserHardDeleteRepository repository,
		RedisAuthSessionStore sessionStore,
		SseConnectionRegistry sseConnectionRegistry,
		S3FileDeletionService s3FileDeletionService,
		@Qualifier("fileCleanupTaskExecutor") Executor cleanupExecutor
	) {
		this.repository = repository;
		this.sessionStore = sessionStore;
		this.sseConnectionRegistry = sseConnectionRegistry;
		this.s3FileDeletionService = s3FileDeletionService;
		this.cleanupExecutor = cleanupExecutor;
	}

	@Transactional
	public void hardDelete(AuthenticatedUser principal, Long userId, String confirmationEmail) {
		HardDeleteTarget target = repository.findForHardDelete(userId)
			.orElseThrow(AdminUserNotFoundException::new);
		if (principal.userId().equals(userId)) {
			throw new CannotDeleteSelfException();
		}
		String normalizedConfirmationEmail = AuthEmailNormalizer.normalize(confirmationEmail);
		if (target.email() == null) {
			throw new HardDeleteConfirmationMismatchException();
		}
		String normalizedTargetEmail = AuthEmailNormalizer.normalize(target.email());
		if (!normalizedConfirmationEmail.equals(normalizedTargetEmail)) {
			throw new HardDeleteConfirmationMismatchException();
		}
		if (target.role() == UserRole.admin) {
			throw new CannotHardDeleteUserException("Admin users cannot be hard deleted");
		}
		if (repository.isReferencedAsActor(userId)) {
			throw new CannotHardDeleteUserException("User is referenced as an administrative actor");
		}

		log.info("Admin hard deleting user. adminUserId={} targetUserId={}", principal.userId(), userId);
		List<String> s3Keys = repository.hardDelete(userId);
		cleanupAfterCommit(userId, s3Keys);
	}

	private void cleanupAfterCommit(Long userId, List<String> s3Keys) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			scheduleCleanup(userId, s3Keys);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				scheduleCleanup(userId, s3Keys);
			}
		});
	}

	private void scheduleCleanup(Long userId, List<String> s3Keys) {
		try {
			cleanupExecutor.execute(() -> revokeSessionsCloseSseAndDeleteS3(userId, s3Keys));
		} catch (RejectedExecutionException exception) {
			log.error("Failed to schedule hard-deleted user cleanup. userId={}, s3KeyCount={}", userId, s3Keys.size(), exception);
		}
	}

	private void revokeSessionsCloseSseAndDeleteS3(Long userId, List<String> s3Keys) {
		try {
			sessionStore.revokeAllSessionsOfUser(userId);
		} catch (RuntimeException exception) {
			log.error("Failed to revoke sessions for hard-deleted user. userId={}", userId, exception);
		}
		try {
			sseConnectionRegistry.closeUser(userId);
		} catch (RuntimeException exception) {
			log.error("Failed to close SSE for hard-deleted user. userId={}", userId, exception);
		}
		for (String s3Key : s3Keys) {
			s3FileDeletionService.deleteOriginAndVariantsLogOnly(s3Key);
		}
	}
}
