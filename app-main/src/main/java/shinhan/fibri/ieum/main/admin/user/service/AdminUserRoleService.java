package shinhan.fibri.ieum.main.admin.user.service;

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
import shinhan.fibri.ieum.main.admin.user.exception.AdminUserNotFoundException;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;

@Service
@RequiredArgsConstructor
public class AdminUserRoleService {

	private static final Logger log = LoggerFactory.getLogger(AdminUserRoleService.class);

	private final UserRepository userRepository;
	private final RedisAuthSessionStore sessionStore;

	@Transactional
	public void changeRole(AuthenticatedUser principal, Long userId, UserRole role) {
		User target = userRepository.findByIdForUpdate(userId)
			.orElseThrow(AdminUserNotFoundException::new);
		UserRole previousRole = target.getRole();
		if (previousRole == role) {
			return;
		}

		target.changeRole(role);
		log.info(
			"Admin changed user role: adminUserId={} targetUserId={} previousRole={} newRole={}",
			principal.userId(),
			userId,
			previousRole,
			role
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
		try {
			sessionStore.revokeAllSessionsOfUser(userId);
		} catch (RuntimeException exception) {
			log.error("Failed to revoke sessions for role-changed user: userId={}", userId, exception);
		}
	}
}
