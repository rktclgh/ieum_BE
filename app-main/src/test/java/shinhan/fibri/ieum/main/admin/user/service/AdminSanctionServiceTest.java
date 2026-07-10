package shinhan.fibri.ieum.main.admin.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import shinhan.fibri.ieum.main.admin.user.exception.SanctionAlreadyActiveException;
import shinhan.fibri.ieum.main.admin.user.exception.UserNotSanctionedException;
import shinhan.fibri.ieum.main.admin.user.repository.UserSanctionRepository;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;

class AdminSanctionServiceTest {

	private final UserRepository userRepository = mock(UserRepository.class);
	private final UserSanctionRepository sanctionRepository = mock(UserSanctionRepository.class);
	private final RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
	private final AdminSanctionService service = new AdminSanctionService(userRepository, sanctionRepository, sessionStore);

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
		when(sanctionRepository.existsByUserIdAndReleasedAtIsNull(10L)).thenReturn(false);
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

		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			synchronization.afterCommit();
		}

		verify(sessionStore).revokeAllSessionsOfUser(10L);
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
	void sanctionAlreadyActiveRevokesSessionsAgainAndThrowsConflict() {
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(user()));
		when(sanctionRepository.existsByUserIdAndReleasedAtIsNull(10L)).thenReturn(true);

		assertThatThrownBy(() -> service.sanction(
			adminPrincipal(),
			10L,
			new CreateSanctionRequest(SanctionType.permanent, "abuse", null)
		)).isInstanceOf(SanctionAlreadyActiveException.class);
		verify(sessionStore).revokeAllSessionsOfUser(10L);
	}

	@Test
	void activateReleasesActiveSanctionAndActivatesUser() {
		User target = user();
		target.suspend();
		UserSanction sanction = UserSanction.permanent(10L, "abuse", 1L);
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));
		when(sanctionRepository.findByUserIdAndReleasedAtIsNull(10L)).thenReturn(Optional.of(sanction));

		service.activate(adminPrincipal(), 10L);

		assertThat(target.getStatus()).isEqualTo(UserStatus.active);
		assertThat(sanction.isActive()).isFalse();
		assertThat(sanction.getReleasedBy()).isEqualTo(1L);
	}

	@Test
	void activateThrowsWhenUserIsAlreadyActiveAndNotSanctioned() {
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(user()));
		when(sanctionRepository.findByUserIdAndReleasedAtIsNull(10L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.activate(adminPrincipal(), 10L))
			.isInstanceOf(UserNotSanctionedException.class);
	}

	private static AuthenticatedUser adminPrincipal() {
		return new AuthenticatedUser(1L, "admin@example.com", UserRole.admin, UserStatus.active);
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
