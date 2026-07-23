package shinhan.fibri.ieum.main.admin.user.service;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.admin.audit.domain.AdminAuditAction;
import shinhan.fibri.ieum.main.admin.audit.repository.AdminAuditLogWriter;
import shinhan.fibri.ieum.main.admin.user.exception.AdminRoleRequiredException;
import shinhan.fibri.ieum.main.admin.user.exception.AdminUserNotFoundException;
import shinhan.fibri.ieum.main.admin.user.exception.CannotChangeOwnRoleException;
import shinhan.fibri.ieum.main.admin.user.exception.CannotPromoteAdminException;
import shinhan.fibri.ieum.main.admin.user.exception.LastAdminRequiredException;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.notification.push.WebPushSubscriptionCleanup;

@Service
@RequiredArgsConstructor
public class AdminUserRoleService {

	private static final Logger log = LoggerFactory.getLogger(AdminUserRoleService.class);

	private final UserRepository userRepository;
	private final RedisAuthSessionStore sessionStore;
	private final AdminAuditLogWriter auditLogWriter;
	private final WebPushSubscriptionCleanup webPushSubscriptionCleanup;

	@Transactional
	public void changeRole(AuthenticatedUser principal, Long userId, UserRole role) {
		List<User> lockedAdmins = userRepository.findAllAdminsForUpdate();
		if (lockedAdmins.stream().noneMatch(admin -> admin.getId().equals(principal.userId()))) {
			throw new AdminRoleRequiredException();
		}

		Optional<User> lockedAdminTarget = lockedAdmins.stream()
			.filter(admin -> admin.getId().equals(userId))
			.findFirst();
		if (role == UserRole.user && lockedAdminTarget.isPresent() && lockedAdmins.size() == 1) {
			throw new LastAdminRequiredException();
		}
		if (role == UserRole.user && principal.userId().equals(userId)) {
			throw new CannotChangeOwnRoleException();
		}

		User target = lockedAdminTarget.orElseGet(() -> userRepository.findByIdForUpdate(userId)
			.orElseThrow(AdminUserNotFoundException::new));
		UserRole previousRole = target.getRole();
		if (previousRole == role) {
			return;
		}

		target.changeRole(role);
		auditLogWriter.append(
			principal.userId(),
			AdminAuditAction.USER_ROLE_CHANGED,
			"user",
			userId,
			java.util.Map.of("previousRole", previousRole.name(), "newRole", role.name())
		);
		log.info(
			"Admin changed user role: adminUserId={} targetUserId={} previousRole={} newRole={}",
			principal.userId(),
			userId,
			previousRole,
			role
		);

		revokeSessionsAfterCommit(userId);
	}

	@Transactional
	public void promoteToAdmin(AuthenticatedUser principal, Long userId) {
		List<User> lockedAdmins = userRepository.findAllAdminsForUpdate();
		if (lockedAdmins.stream().noneMatch(admin -> admin.getId().equals(principal.userId()))) {
			throw new AdminRoleRequiredException();
		}
		if (lockedAdmins.stream().anyMatch(admin -> admin.getId().equals(userId))) {
			throw new CannotPromoteAdminException();
		}

		User target = userRepository.findByIdForUpdate(userId)
			.orElseThrow(AdminUserNotFoundException::new);
		if (target.getStatus() != shinhan.fibri.ieum.common.auth.domain.UserStatus.active
			|| target.getRole() != UserRole.user) {
			throw new CannotPromoteAdminException();
		}
		UserRole previousRole = target.getRole();
		target.changeRole(UserRole.admin);
		auditLogWriter.append(
			principal.userId(),
			AdminAuditAction.USER_PROMOTED_TO_ADMIN,
			"user",
			userId,
			java.util.Map.of("previousRole", previousRole.name(), "newRole", UserRole.admin.name())
		);
		log.info(
			"Admin promoted user to administrator: adminUserId={} targetUserId={}",
			principal.userId(),
			userId
		);

		revokeSessionsAfterCommit(userId);
	}

	private void revokeSessionsAfterCommit(Long userId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			revokeSessions(userId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				revokeSessions(userId);
			}
		});
	}

	private void revokeSessions(Long userId) {
		runSafely(
			"admin_role_session_revoke_failed",
			userId,
			() -> sessionStore.revokeAllSessionsOfUser(userId)
		);
		runSafely(
			"admin_role_push_cleanup_failed",
			userId,
			() -> webPushSubscriptionCleanup.deleteForUser(userId)
		);
	}

	private void runSafely(String event, Long userId, Runnable action) {
		try {
			action.run();
		} catch (RuntimeException exception) {
			log.error(
				"event={} userId={} failureClass={}",
				event,
				userId,
				exception.getClass().getSimpleName()
			);
		}
	}
}
