package shinhan.fibri.ieum.main.admin.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.admin.user.domain.SanctionType;
import shinhan.fibri.ieum.main.admin.user.domain.UserSanction;
import shinhan.fibri.ieum.main.admin.user.dto.CreateSanctionRequest;
import shinhan.fibri.ieum.main.admin.user.dto.CreateSanctionResponse;
import shinhan.fibri.ieum.main.admin.user.exception.CannotSanctionAdminException;
import shinhan.fibri.ieum.main.admin.user.exception.InvalidSanctionRequestException;
import shinhan.fibri.ieum.main.admin.user.exception.UserNotSanctionedException;
import shinhan.fibri.ieum.main.admin.user.repository.UserSanctionRepository;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;

class AdminSanctionServiceTest {

	private final UserRepository userRepository = mock(UserRepository.class);
	private final UserSanctionRepository sanctionRepository = mock(UserSanctionRepository.class);
	private final RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
	private final SseConnectionRegistry sseConnectionRegistry = mock(SseConnectionRegistry.class);
	private final AdminSanctionService service = new AdminSanctionService(
		userRepository,
		sanctionRepository,
		sessionStore,
		sseConnectionRegistry
	);

	@AfterEach
	void clearSynchronization() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void sanctionSuspendsUserAndRevokesSessionsAfterCommit() {
		TransactionSynchronizationManager.initSynchronization();
		User target = user();
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));
		when(sanctionRepository.save(any(UserSanction.class))).thenAnswer(invocation -> {
			UserSanction sanction = invocation.getArgument(0);
			ReflectionTestUtils.setField(sanction, "id", 99L);
			return sanction;
		});

		CreateSanctionResponse response = service.sanction(
			adminPrincipal(),
			10L,
			new CreateSanctionRequest(
				SanctionType.temporary,
				"abuse",
				OffsetDateTime.now().plusDays(1)
			)
		);

		assertThat(response.sanctionId()).isEqualTo(99L);
		assertThat(target.getStatus()).isEqualTo(UserStatus.suspended);
		verify(sessionStore, never()).revokeAllSessionsOfUser(10L);
		verify(sseConnectionRegistry, never()).closeUser(10L);

		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			synchronization.afterCommit();
		}

		verify(sessionStore).revokeAllSessionsOfUser(10L);
		verify(sseConnectionRegistry).closeUser(10L);
	}

	@Test
	void sanctionRejectsAdminTarget() {
		User target = user();
		ReflectionTestUtils.setField(target, "role", UserRole.admin);
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));

		assertThatThrownBy(() -> service.sanction(
			adminPrincipal(),
			10L,
			new CreateSanctionRequest(SanctionType.permanent, "abuse", null)
		)).isInstanceOf(CannotSanctionAdminException.class);
	}

	@Test
	void sanctionRejectsTemporaryWithoutEndsAt() {
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(user()));

		assertThatThrownBy(() -> service.sanction(
			adminPrincipal(),
			10L,
			new CreateSanctionRequest(SanctionType.temporary, "abuse", null)
		)).isInstanceOf(InvalidSanctionRequestException.class)
			.hasMessage("endsAt is required for temporary sanction");
	}

	@Test
	void sanctionRejectsTemporaryWithPastEndsAt() {
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(user()));

		assertThatThrownBy(() -> service.sanction(
			adminPrincipal(),
			10L,
			new CreateSanctionRequest(SanctionType.temporary, "abuse", OffsetDateTime.now().minusMinutes(1))
		)).isInstanceOf(InvalidSanctionRequestException.class)
			.hasMessage("endsAt must be in the future");
	}

	@Test
	void sanctionRejectsPermanentWithEndsAt() {
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(user()));

		assertThatThrownBy(() -> service.sanction(
			adminPrincipal(),
			10L,
			new CreateSanctionRequest(SanctionType.permanent, "abuse", OffsetDateTime.now().plusDays(1))
		)).isInstanceOf(InvalidSanctionRequestException.class)
			.hasMessage("endsAt is not allowed for permanent sanction");
	}

	@Test
	void sanctionAllowsAdditionalActiveSanctionForCumulativeLedger() {
		User target = user();
		target.suspend();
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));
		when(sanctionRepository.save(any(UserSanction.class))).thenAnswer(invocation -> {
			UserSanction sanction = invocation.getArgument(0);
			ReflectionTestUtils.setField(sanction, "id", 100L);
			return sanction;
		});

		CreateSanctionResponse response = service.sanction(
			adminPrincipal(),
			10L,
			new CreateSanctionRequest(SanctionType.permanent, "abuse", null)
		);

		assertThat(response.sanctionId()).isEqualTo(100L);
		assertThat(target.getStatus()).isEqualTo(UserStatus.suspended);
		verify(sessionStore).revokeAllSessionsOfUser(10L);
	}

	@Test
	void sanctionClosesSseEvenWhenSessionRevocationFails() {
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(user()));
		when(sanctionRepository.save(any(UserSanction.class))).thenAnswer(invocation -> invocation.getArgument(0));
		doThrow(new IllegalStateException("redis unavailable"))
			.when(sessionStore).revokeAllSessionsOfUser(10L);

		service.sanction(
			adminPrincipal(),
			10L,
			new CreateSanctionRequest(SanctionType.permanent, "abuse", null)
		);

		verify(sseConnectionRegistry).closeUser(10L);
	}

	@Test
	void sanctionDoesNotRevokeSessionsOrCloseSseWhenTheTransactionWillRollBack() {
		TransactionSynchronizationManager.initSynchronization();
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(user()));
		when(sanctionRepository.save(any(UserSanction.class)))
			.thenThrow(new DataIntegrityViolationException("duplicate"));

		assertThatThrownBy(() -> service.sanction(
			adminPrincipal(),
			10L,
			new CreateSanctionRequest(SanctionType.permanent, "abuse", null)
		)).isInstanceOf(DataIntegrityViolationException.class);

		verify(sessionStore, never()).revokeAllSessionsOfUser(10L);
		verify(sseConnectionRegistry, never()).closeUser(10L);
		assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
	}

	@Test
	void activateReleasesActiveSanctionAndActivatesUser() {
		User target = user();
		target.suspend();
		UserSanction sanction = UserSanction.permanent(10L, "abuse", 1L);
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));
		when(sanctionRepository.findByUserIdAndRevokedAtIsNullForUpdate(10L)).thenReturn(List.of(sanction));

		service.activate(adminPrincipal(), 10L);

		assertThat(target.getStatus()).isEqualTo(UserStatus.active);
		assertThat(sanction.isActive()).isFalse();
		assertThat(sanction.getReleasedBy()).isEqualTo(1L);
	}

	@Test
	void activateRevokesSessionsAfterCommit() {
		TransactionSynchronizationManager.initSynchronization();
		User target = user();
		target.suspend();
		UserSanction sanction = UserSanction.permanent(10L, "abuse", 1L);
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));
		when(sanctionRepository.findByUserIdAndRevokedAtIsNullForUpdate(10L)).thenReturn(List.of(sanction));

		service.activate(adminPrincipal(), 10L);

		verify(sessionStore, never()).revokeAllSessionsOfUser(10L);

		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			synchronization.afterCommit();
		}

		verify(sessionStore).revokeAllSessionsOfUser(10L);
	}

	@Test
	void activateThrowsWhenUserIsAlreadyActiveAndNotSanctioned() {
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(user()));
		when(sanctionRepository.findByUserIdAndRevokedAtIsNullForUpdate(10L)).thenReturn(List.of());

		assertThatThrownBy(() -> service.activate(adminPrincipal(), 10L))
			.isInstanceOf(UserNotSanctionedException.class);
	}

	@Test
	void activateWithoutActiveSanctionButSuspendedRecoversStatusOnly() {
		// 드리프트 복구 분기 b: 활성 제재 기록은 없는데 status만 suspended로 남아있는 경우
		// (수동 DB 변경 등으로 어긋난 상태) — 제재 해제 없이 status만 복구한다.
		User target = user();
		target.suspend();
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));
		when(sanctionRepository.findByUserIdAndRevokedAtIsNullForUpdate(10L)).thenReturn(List.of());

		service.activate(adminPrincipal(), 10L);

		assertThat(target.getStatus()).isEqualTo(UserStatus.active);
	}

	@Test
	void activateWithActiveSanctionButAlreadyActiveReleasesSanctionOnly() {
		// 드리프트 복구 분기 c: status는 이미 active인데 활성 제재 기록이 남아있는 경우 —
		// status 변경 없이 제재만 release하고 예외 없이 끝낸다.
		User target = user();
		UserSanction sanction = UserSanction.permanent(10L, "abuse", 1L);
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));
		when(sanctionRepository.findByUserIdAndRevokedAtIsNullForUpdate(10L)).thenReturn(List.of(sanction));

		service.activate(adminPrincipal(), 10L);

		assertThat(target.getStatus()).isEqualTo(UserStatus.active);
		assertThat(sanction.isActive()).isFalse();
		assertThat(sanction.getReleasedBy()).isEqualTo(1L);
	}

	@Test
	void releaseExpiredSanctionPreservesHistoryAndActivatesUserWhenNoEffectiveSanctionRemains() {
		User target = user();
		target.suspend();
		UserSanction sanction = expiredTemporarySanction();
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));
		when(sanctionRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(sanction));
		when(sanctionRepository.existsEffectiveSanction(any(), any())).thenReturn(false);

		service.releaseExpiredSanction(99L, 10L);

		assertThat(sanction.getReleasedAt()).isNull();
		assertThat(sanction.getRevokedAt()).isNull();
		assertThat(target.getStatus()).isEqualTo(UserStatus.active);
	}

	@Test
	void releaseExpiredSanctionKeepsUserSuspendedWhenAnotherEffectiveSanctionExists() {
		User target = user();
		target.suspend();
		UserSanction sanction = expiredTemporarySanction();
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));
		when(sanctionRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(sanction));
		when(sanctionRepository.existsEffectiveSanction(any(), any())).thenReturn(true);

		service.releaseExpiredSanction(99L, 10L);

		assertThat(sanction.getReleasedAt()).isNull();
		assertThat(sanction.getRevokedAt()).isNull();
		assertThat(target.getStatus()).isEqualTo(UserStatus.suspended);
	}

	@Test
	void releaseExpiredSanctionDoesNothingBeforeTheSentenceEnds() {
		User target = user();
		target.suspend();
		UserSanction sanction = UserSanction.temporary(10L, "abuse", 1L, OffsetDateTime.now().plusHours(1));
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));
		when(sanctionRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(sanction));

		service.releaseExpiredSanction(99L, 10L);

		assertThat(target.getStatus()).isEqualTo(UserStatus.suspended);
		verify(sanctionRepository, never()).existsEffectiveSanction(any(), any());
	}

	@Test
	void activateReleasesEveryUnrevokedSanctionBeforeActivatingUser() {
		User target = user();
		target.suspend();
		UserSanction first = UserSanction.permanent(10L, "abuse", 1L);
		UserSanction second = UserSanction.temporary(10L, "spam", 1L, OffsetDateTime.now().plusDays(1));
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));
		when(sanctionRepository.findByUserIdAndRevokedAtIsNullForUpdate(10L)).thenReturn(List.of(first, second));

		service.activate(adminPrincipal(), 10L);

		assertThat(first.isActive()).isFalse();
		assertThat(second.isActive()).isFalse();
		assertThat(first.getReleasedBy()).isEqualTo(1L);
		assertThat(second.getReleasedBy()).isEqualTo(1L);
		assertThat(target.getStatus()).isEqualTo(UserStatus.active);
	}

	@Test
	void releaseExpiredSanctionWarnsAndSkipsStatusRecoveryWhenUserMissing() {
		UserSanction sanction = expiredTemporarySanction();
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.empty());
		when(sanctionRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(sanction));

		service.releaseExpiredSanction(99L, 10L);

		assertThat(sanction.getReleasedAt()).isNull();
		assertThat(sanction.getRevokedAt()).isNull();
	}

	@Test
	void releaseExpiredSanctionIsNoopWhenAlreadyReleased() {
		UserSanction sanction = expiredTemporarySanction();
		sanction.release(OffsetDateTime.now(), 1L);
		User target = user();
		target.suspend();
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));
		when(sanctionRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(sanction));

		service.releaseExpiredSanction(99L, 10L);

		assertThat(target.getStatus()).isEqualTo(UserStatus.suspended);
		assertThat(sanction.getReleasedBy()).isEqualTo(1L);
	}

	private static AuthenticatedUser adminPrincipal() {
		return new AuthenticatedUser(1L, "admin@example.com", UserRole.admin, UserStatus.active);
	}

	private static UserSanction expiredTemporarySanction() {
		OffsetDateTime endsAt = OffsetDateTime.now().minusMinutes(1);
		return UserSanction.aiTemporary(
			10L,
			20L,
			"abuse",
			endsAt.minusHours(2),
			endsAt.minusHours(1),
			endsAt
		);
	}

	private static User user() {
		return User.createEmailUser(
			"user@example.com",
			"hash",
			"user",
			LocalDate.of(2000, 1, 1),
			GenderType.female,
			"KR"
		);
	}
}
