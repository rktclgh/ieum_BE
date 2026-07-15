package shinhan.fibri.ieum.main.admin.user.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.user.exception.AdminUserNotFoundException;
import shinhan.fibri.ieum.main.admin.user.exception.CannotDeleteSelfException;
import shinhan.fibri.ieum.main.admin.user.exception.CannotHardDeleteUserException;
import shinhan.fibri.ieum.main.admin.user.exception.HardDeleteConfirmationMismatchException;
import shinhan.fibri.ieum.main.admin.user.repository.AdminUserHardDeleteRepository;
import shinhan.fibri.ieum.main.admin.user.repository.HardDeleteTarget;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.file.service.FileObjectKeys;
import shinhan.fibri.ieum.main.file.service.FileVariant;
import shinhan.fibri.ieum.main.file.storage.FileStorage;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;

@Service
@RequiredArgsConstructor
public class AdminUserHardDeleteService {

	private static final Logger log = LoggerFactory.getLogger(AdminUserHardDeleteService.class);

	private final AdminUserHardDeleteRepository repository;
	private final RedisAuthSessionStore sessionStore;
	private final SseConnectionRegistry sseConnectionRegistry;
	private final FileStorage fileStorage;

	@Transactional
	public void hardDelete(AuthenticatedUser principal, Long userId, String confirmationEmail) {
		HardDeleteTarget target = repository.findForHardDelete(userId)
			.orElseThrow(AdminUserNotFoundException::new);
		if (principal.userId().equals(userId)) {
			throw new CannotDeleteSelfException();
		}
		if (!target.email().equals(confirmationEmail)) {
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
			revokeSessionsCloseSseAndDeleteS3(userId, s3Keys);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				revokeSessionsCloseSseAndDeleteS3(userId, s3Keys);
			}
		});
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
			deleteLogOnly(s3Key);
			deleteLogOnly(FileObjectKeys.variantKey(s3Key, FileVariant.DISPLAY));
			deleteLogOnly(FileObjectKeys.variantKey(s3Key, FileVariant.THUMB));
		}
	}

	private void deleteLogOnly(String s3Key) {
		try {
			fileStorage.delete(s3Key);
		} catch (RuntimeException exception) {
			log.warn("Failed to delete hard-deleted user file object. s3Key={}", s3Key, exception);
		}
	}
}
