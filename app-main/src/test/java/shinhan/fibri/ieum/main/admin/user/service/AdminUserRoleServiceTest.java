package shinhan.fibri.ieum.main.admin.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
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
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;

class AdminUserRoleServiceTest {

	private final UserRepository userRepository = mock(UserRepository.class);
	private final RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
	private final AdminUserRoleService service = new AdminUserRoleService(userRepository, sessionStore);

	@AfterEach
	void clearSynchronization() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void downgradeAdminToUserRevokesSessionsAfterCommit() {
		TransactionSynchronizationManager.initSynchronization();
		User target = user(UserRole.admin);
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));

		service.changeRole(adminPrincipal(), 10L, UserRole.user);

		assertThat(target.getRole()).isEqualTo(UserRole.user);
		verify(sessionStore, never()).revokeAllSessionsOfUser(10L);

		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			synchronization.afterCommit();
		}

		verify(sessionStore).revokeAllSessionsOfUser(10L);
	}

	@Test
	void promoteUserToAdminRevokesSessionsAfterCommit() {
		TransactionSynchronizationManager.initSynchronization();
		User target = user(UserRole.user);
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));

		service.changeRole(adminPrincipal(), 10L, UserRole.admin);

		assertThat(target.getRole()).isEqualTo(UserRole.admin);
		verify(sessionStore, never()).revokeAllSessionsOfUser(10L);

		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			synchronization.afterCommit();
		}

		verify(sessionStore).revokeAllSessionsOfUser(10L);
	}

	@Test
	void sameRoleDoesNotRevokeSessions() {
		User target = user(UserRole.user);
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));

		service.changeRole(adminPrincipal(), 10L, UserRole.user);

		assertThat(target.getRole()).isEqualTo(UserRole.user);
		verify(sessionStore, never()).revokeAllSessionsOfUser(10L);
	}

	private static AuthenticatedUser adminPrincipal() {
		return new AuthenticatedUser(1L, "admin@example.com", UserRole.admin, UserStatus.active);
	}

	private static User user(UserRole role) {
		User user = User.createEmailUser(
			"user@example.com",
			"hash",
			"user",
			LocalDate.of(2000, 1, 1),
			GenderType.female,
			"KR"
		);
		ReflectionTestUtils.setField(user, "id", 10L);
		ReflectionTestUtils.setField(user, "role", role);
		return user;
	}
}
