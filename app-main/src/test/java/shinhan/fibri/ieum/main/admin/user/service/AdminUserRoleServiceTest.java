package shinhan.fibri.ieum.main.admin.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.admin.audit.domain.AdminAuditAction;
import shinhan.fibri.ieum.main.admin.audit.repository.AdminAuditLogWriter;
import shinhan.fibri.ieum.main.admin.user.exception.AdminRoleRequiredException;
import shinhan.fibri.ieum.main.admin.user.exception.AdminUserNotFoundException;
import shinhan.fibri.ieum.main.admin.user.exception.CannotChangeOwnRoleException;
import shinhan.fibri.ieum.main.admin.user.exception.LastAdminRequiredException;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;

class AdminUserRoleServiceTest {

	private final UserRepository userRepository = mock(UserRepository.class);
	private final RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
	private final AdminAuditLogWriter auditLogWriter = mock(AdminAuditLogWriter.class);
	private final AdminUserRoleService service = new AdminUserRoleService(userRepository, sessionStore, auditLogWriter);

	@AfterEach
	void clearSynchronization() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void selfDemotionIsRejectedWhenAnotherAdministratorRemains() {
		TransactionSynchronizationManager.initSynchronization();
		User actor = user(1L, UserRole.admin);
		User otherAdmin = user(2L, UserRole.admin);
		when(userRepository.findAllAdminsForUpdate()).thenReturn(List.of(actor, otherAdmin));

		assertThatThrownBy(() -> service.changeRole(adminPrincipal(), 1L, UserRole.user))
			.isInstanceOf(CannotChangeOwnRoleException.class);

		assertThat(actor.getRole()).isEqualTo(UserRole.admin);
		assertThat(actor.getAuthVersion()).isZero();
		assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
		verify(userRepository, never()).findByIdForUpdate(1L);
		verify(sessionStore, never()).revokeAllSessionsOfUser(1L);
		verify(auditLogWriter, never()).append(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void finalAdminDemotionIsRejectedAfterCanonicalActorReadinessCheck() {
		TransactionSynchronizationManager.initSynchronization();
		User finalAdmin = user(1L, UserRole.admin);
		when(userRepository.findAllAdminsForUpdate()).thenReturn(List.of(finalAdmin));

		assertThatThrownBy(() -> service.changeRole(adminPrincipal(), 1L, UserRole.user))
			.isInstanceOf(LastAdminRequiredException.class);

		assertThat(finalAdmin.getRole()).isEqualTo(UserRole.admin);
		assertThat(finalAdmin.getAuthVersion()).isZero();
		assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
		verify(sessionStore, never()).revokeAllSessionsOfUser(1L);
	}

	@Test
	void demoteOtherAdminRevokesSessionsOnlyAfterCommit() {
		TransactionSynchronizationManager.initSynchronization();
		User actor = user(1L, UserRole.admin);
		User target = user(10L, UserRole.admin);
		when(userRepository.findAllAdminsForUpdate()).thenReturn(List.of(actor, target));

		service.changeRole(adminPrincipal(), 10L, UserRole.user);

		assertThat(target.getRole()).isEqualTo(UserRole.user);
		assertThat(target.getAuthVersion()).isEqualTo(1L);
		verify(auditLogWriter).append(
			1L,
			AdminAuditAction.USER_ROLE_CHANGED,
			"user",
			10L,
			java.util.Map.of("previousRole", "admin", "newRole", "user")
		);
		verify(userRepository, never()).findByIdForUpdate(10L);
		verify(sessionStore, never()).revokeAllSessionsOfUser(10L);

		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			synchronization.afterCommit();
		}

		verify(sessionStore).revokeAllSessionsOfUser(10L);
	}

	@Test
	void promoteUserToAdminRevokesSessionsAfterCommit() {
		TransactionSynchronizationManager.initSynchronization();
		User actor = user(1L, UserRole.admin);
		User target = user(10L, UserRole.user);
		when(userRepository.findAllAdminsForUpdate()).thenReturn(List.of(actor));
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));

		service.changeRole(adminPrincipal(), 10L, UserRole.admin);

		assertThat(target.getRole()).isEqualTo(UserRole.admin);
		assertThat(target.getAuthVersion()).isEqualTo(1L);
		verify(auditLogWriter).append(
			1L,
			AdminAuditAction.USER_ROLE_CHANGED,
			"user",
			10L,
			java.util.Map.of("previousRole", "user", "newRole", "admin")
		);
		InOrder order = inOrder(userRepository);
		order.verify(userRepository).findAllAdminsForUpdate();
		order.verify(userRepository).findByIdForUpdate(10L);
		verify(sessionStore, never()).revokeAllSessionsOfUser(10L);

		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			synchronization.afterCommit();
		}

		verify(sessionStore).revokeAllSessionsOfUser(10L);
	}

	@Test
	void sameRoleIsNoOpWithoutAuthVersionChangeOrSessionCleanup() {
		TransactionSynchronizationManager.initSynchronization();
		User actor = user(1L, UserRole.admin);
		User target = user(10L, UserRole.user);
		when(userRepository.findAllAdminsForUpdate()).thenReturn(List.of(actor));
		when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));

		service.changeRole(adminPrincipal(), 10L, UserRole.user);

		assertThat(target.getRole()).isEqualTo(UserRole.user);
		assertThat(target.getAuthVersion()).isZero();
		assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
		verify(sessionStore, never()).revokeAllSessionsOfUser(10L);
		verify(auditLogWriter, never()).append(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void staleAdminPrincipalIsRejectedBeforeSelfDemotionGuard() {
		TransactionSynchronizationManager.initSynchronization();
		User remainingAdmin = user(2L, UserRole.admin);
		when(userRepository.findAllAdminsForUpdate()).thenReturn(List.of(remainingAdmin));

		assertThatThrownBy(() -> service.changeRole(adminPrincipal(), 1L, UserRole.user))
			.isInstanceOf(AdminRoleRequiredException.class);

		assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
		verify(userRepository, never()).findByIdForUpdate(1L);
		verify(sessionStore, never()).revokeAllSessionsOfUser(1L);
	}

	@Test
	void missingTargetIsRejectedAfterAdminLocksAndActorReadinessCheck() {
		User actor = user(1L, UserRole.admin);
		when(userRepository.findAllAdminsForUpdate()).thenReturn(List.of(actor));
		when(userRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.changeRole(adminPrincipal(), 99L, UserRole.admin))
			.isInstanceOf(AdminUserNotFoundException.class);
	}

	private static AuthenticatedUser adminPrincipal() {
		return new AuthenticatedUser(1L, "admin@example.com", UserRole.admin, UserStatus.active);
	}

	private static User user(Long userId, UserRole role) {
		User user = User.createEmailUser(
			"user%d@example.com".formatted(userId),
			"hash",
			"user%d".formatted(userId),
			LocalDate.of(2000, 1, 1),
			GenderType.female,
			"KR"
		);
		ReflectionTestUtils.setField(user, "id", userId);
		ReflectionTestUtils.setField(user, "role", role);
		return user;
	}
}
