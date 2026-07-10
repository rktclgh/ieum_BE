package shinhan.fibri.ieum.main.admin.user.service;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.admin.user.domain.SanctionType;
import shinhan.fibri.ieum.main.admin.user.domain.UserSanction;
import shinhan.fibri.ieum.main.admin.user.dto.CreateSanctionRequest;
import shinhan.fibri.ieum.main.admin.user.dto.CreateSanctionResponse;
import shinhan.fibri.ieum.main.admin.user.exception.AdminUserNotFoundException;
import shinhan.fibri.ieum.main.admin.user.exception.CannotSanctionAdminException;
import shinhan.fibri.ieum.main.admin.user.exception.InvalidSanctionRequestException;
import shinhan.fibri.ieum.main.admin.user.exception.SanctionAlreadyActiveException;
import shinhan.fibri.ieum.main.admin.user.exception.UserNotSanctionedException;
import shinhan.fibri.ieum.main.admin.user.repository.UserSanctionRepository;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;

@Service
@RequiredArgsConstructor
public class AdminSanctionService {

	private static final Logger log = LoggerFactory.getLogger(AdminSanctionService.class);

	private final UserRepository userRepository;
	private final UserSanctionRepository userSanctionRepository;
	private final RedisAuthSessionStore sessionStore;

	@Transactional
	public CreateSanctionResponse sanction(AuthenticatedUser principal, Long userId, CreateSanctionRequest request) {
		User target = userRepository.findByIdForUpdate(userId)
			.orElseThrow(AdminUserNotFoundException::new);
		if (target.getRole() == UserRole.admin) {
			throw new CannotSanctionAdminException();
		}
		validateRequest(request);
		if (userSanctionRepository.existsByUserIdAndReleasedAtIsNull(userId)) {
			revokeSessions(userId);
			throw new SanctionAlreadyActiveException();
		}

		target.suspend();
		UserSanction sanction = createSanction(principal, userId, request);
		try {
			UserSanction saved = userSanctionRepository.save(sanction);
			revokeSessionsAfterCommit(userId);
			return new CreateSanctionResponse(saved.getId());
		} catch (DataIntegrityViolationException exception) {
			revokeSessions(userId);
			throw new SanctionAlreadyActiveException();
		}
	}

	@Transactional
	public void activate(AuthenticatedUser principal, Long userId) {
		User target = userRepository.findByIdForUpdate(userId)
			.orElseThrow(AdminUserNotFoundException::new);
		UserSanction activeSanction = userSanctionRepository.findByUserIdAndReleasedAtIsNull(userId).orElse(null);
		if (activeSanction == null && target.getStatus() == UserStatus.active) {
			throw new UserNotSanctionedException();
		}
		if (activeSanction != null) {
			activeSanction.release(OffsetDateTime.now(), principal.userId());
			if (target.getStatus() == UserStatus.active) {
				log.warn("Activating user with active sanction but active status: userId={}", userId);
				return;
			}
		} else {
			log.warn("Activating suspended user without active sanction: userId={}", userId);
		}
		target.activate();
	}

	@Transactional
	public void releaseExpiredSanction(Long sanctionId) {
		UserSanction sanction = userSanctionRepository.findByIdForUpdate(sanctionId).orElse(null);
		if (sanction == null || !sanction.isActive()) {
			return;
		}
		sanction.release(OffsetDateTime.now(), null);
		userRepository.findByIdForUpdate(sanction.getUserId())
			.ifPresentOrElse(User::activate, () -> log.warn(
				"Expired sanction released but user was not found: userId={}, sanctionId={}",
				sanction.getUserId(),
				sanctionId
			));
	}

	private void validateRequest(CreateSanctionRequest request) {
		if (request.type() == SanctionType.temporary) {
			if (request.endsAt() == null) {
				throw new InvalidSanctionRequestException("endsAt", "endsAt is required for temporary sanction");
			}
			if (!request.endsAt().isAfter(OffsetDateTime.now())) {
				throw new InvalidSanctionRequestException("endsAt", "endsAt must be in the future");
			}
			return;
		}
		if (request.endsAt() != null) {
			throw new InvalidSanctionRequestException("endsAt", "endsAt is not allowed for permanent sanction");
		}
	}

	private UserSanction createSanction(AuthenticatedUser principal, Long userId, CreateSanctionRequest request) {
		if (request.type() == SanctionType.temporary) {
			return UserSanction.temporary(userId, request.reason(), principal.userId(), request.endsAt());
		}
		return UserSanction.permanent(userId, request.reason(), principal.userId());
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
			log.error("Failed to revoke sessions for sanctioned user: userId={}", userId, exception);
		}
	}
}
