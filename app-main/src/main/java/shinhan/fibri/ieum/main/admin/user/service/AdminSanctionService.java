package shinhan.fibri.ieum.main.admin.user.service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.admin.audit.domain.AdminAuditAction;
import shinhan.fibri.ieum.main.admin.audit.repository.AdminAuditLogWriter;
import shinhan.fibri.ieum.main.admin.user.domain.SanctionType;
import shinhan.fibri.ieum.main.admin.user.domain.UserSanction;
import shinhan.fibri.ieum.main.admin.user.dto.CreateSanctionRequest;
import shinhan.fibri.ieum.main.admin.user.dto.CreateSanctionResponse;
import shinhan.fibri.ieum.main.admin.user.exception.AdminUserNotFoundException;
import shinhan.fibri.ieum.main.admin.user.exception.CannotSanctionAdminException;
import shinhan.fibri.ieum.main.admin.user.exception.InvalidSanctionRequestException;
import shinhan.fibri.ieum.main.admin.user.exception.UserNotSanctionedException;
import shinhan.fibri.ieum.main.admin.user.repository.UserSanctionRepository;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;

@Service
@RequiredArgsConstructor
public class AdminSanctionService {

	private static final Logger log = LoggerFactory.getLogger(AdminSanctionService.class);

	private final UserRepository userRepository;
	private final UserSanctionRepository userSanctionRepository;
	private final RedisAuthSessionStore sessionStore;
	private final SseConnectionRegistry sseConnectionRegistry;
	private final AdminAuditLogWriter auditLogWriter;

	@Transactional
	public CreateSanctionResponse sanction(AuthenticatedUser principal, Long userId, CreateSanctionRequest request) {
		User target = userRepository.findByIdForUpdate(userId)
			.orElseThrow(AdminUserNotFoundException::new);
		if (target.getRole() == UserRole.admin) {
			throw new CannotSanctionAdminException();
		}
		validateRequest(request);
		target.suspend();
		UserSanction sanction = createSanction(principal, userId, request);
		UserSanction saved = userSanctionRepository.save(sanction);
		auditLogWriter.append(
			principal.userId(),
			AdminAuditAction.USER_SANCTION_CREATED,
			"user",
			userId,
			sanctionDetails(saved)
		);
		revokeSessionsAfterCommit(userId);
		return new CreateSanctionResponse(saved.getId());
	}

	@Transactional
	public void activate(AuthenticatedUser principal, Long userId) {
		User target = userRepository.findByIdForUpdate(userId)
			.orElseThrow(AdminUserNotFoundException::new);
		List<UserSanction> activeSanctions = userSanctionRepository.findByUserIdAndRevokedAtIsNullForUpdate(userId);
		UserStatus previousStatus = target.getStatus();
		if (activeSanctions.isEmpty() && target.getStatus() == UserStatus.active) {
			throw new UserNotSanctionedException();
		}
		if (!activeSanctions.isEmpty()) {
			OffsetDateTime now = OffsetDateTime.now();
			activeSanctions.forEach(sanction -> sanction.release(now, principal.userId()));
			if (target.getStatus() == UserStatus.active) {
				log.warn("Activating user with active sanction but active status: userId={}", userId);
			} else {
				target.activate();
			}
		} else {
			log.warn("Activating suspended user without active sanction: userId={}", userId);
			target.activate();
		}
		auditLogWriter.append(
			principal.userId(),
			AdminAuditAction.USER_ACTIVATED,
			"user",
			userId,
			Map.of(
				"releasedSanctionIds", activeSanctions.stream().map(UserSanction::getId).toList(),
				"previousStatus", previousStatus.name(),
				"newStatus", target.getStatus().name()
			)
		);
		revokeSessionsAfterCommit(userId);
	}

	@Transactional
	public void releaseExpiredSanction(Long sanctionId, Long userId) {
		// user 락을 sanction 락보다 먼저 잡는다 — sanction()/activate()와 락 순서를 통일해
		// 만료 시점에 관리자의 activate 호출과 겹쳐도 AB-BA 데드락이 나지 않게 한다.
		Optional<User> target = userRepository.findByIdForUpdate(userId);
		if (target.isEmpty()) {
			log.warn("Expired sanction found but user was not found: userId={}, sanctionId={}", userId, sanctionId);
			return;
		}
		UserSanction sanction = userSanctionRepository.findByIdForUpdate(sanctionId).orElse(null);
		OffsetDateTime now = OffsetDateTime.now();
		if (sanction == null
				|| !sanction.isActive()
				|| !userId.equals(sanction.getUserId())
				|| sanction.getType() != SanctionType.temporary
				|| sanction.getEndsAt() == null
				|| sanction.getEndsAt().isAfter(now)) {
			return;
		}
		if (!userSanctionRepository.existsEffectiveSanction(userId, now)) {
			target.orElseThrow().activate();
		}
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

	private Map<String, Object> sanctionDetails(UserSanction sanction) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("sanctionId", sanction.getId());
		details.put("type", sanction.getType().name());
		details.put("reason", sanction.getReason());
		details.put("endsAt", sanction.getEndsAt() == null ? null : sanction.getEndsAt().toString());
		return details;
	}

	private void revokeSessionsAfterCommit(Long userId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			revokeSessionsAndCloseSse(userId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				revokeSessionsAndCloseSse(userId);
			}
		});
	}

	private void revokeSessionsAndCloseSse(Long userId) {
		try {
			sessionStore.revokeAllSessionsOfUser(userId);
		} catch (RuntimeException exception) {
			log.error("Failed to revoke sessions for sanctioned user: userId={}", userId, exception);
		}
		try {
			sseConnectionRegistry.closeUser(userId);
		} catch (RuntimeException exception) {
			log.error("Failed to close SSE for sanctioned user: userId={}", userId, exception);
		}
	}
}
