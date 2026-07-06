package shinhan.fibri.ieum.main.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.auth.dto.LoginRequest;
import shinhan.fibri.ieum.main.auth.exception.EmailNotVerifiedException;
import shinhan.fibri.ieum.main.auth.exception.InvalidCredentialsException;
import shinhan.fibri.ieum.main.auth.exception.SuspendedUserException;
import shinhan.fibri.ieum.main.auth.repository.LoginLogRepository;
import shinhan.fibri.ieum.main.auth.session.AccessTokenIssuer;
import shinhan.fibri.ieum.main.auth.session.AuthSession;
import shinhan.fibri.ieum.main.auth.session.OpaqueTokenGenerator;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.auth.session.Sha256TokenHasher;

class LoginServiceTest {

	@Test
	void loginThrowsInvalidCredentialsWhenEmailDoesNotExist() {
		UserRepository userRepository = mock(UserRepository.class);
		PasswordHasher passwordHasher = mock(PasswordHasher.class);
		LoginService service = service(userRepository, passwordHasher);
		when(userRepository.findByEmailAndProviderAndDeletedAtIsNull("user@example.com", AuthProvider.email))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.login(new LoginRequest(" USER@example.COM ", "Passw@rd123")))
			.isInstanceOf(InvalidCredentialsException.class);
		verify(passwordHasher).matches(eq("Passw@rd123"), anyString());
	}

	@Test
	void loginThrowsInvalidCredentialsWhenPasswordDoesNotMatch() {
		User user = user();
		UserRepository userRepository = mock(UserRepository.class);
		PasswordHasher passwordHasher = mock(PasswordHasher.class);
		LoginService service = service(userRepository, passwordHasher);
		when(userRepository.findByEmailAndProviderAndDeletedAtIsNull("user@example.com", AuthProvider.email))
			.thenReturn(Optional.of(user));
		when(passwordHasher.matches("wrong-password", "hashed-password")).thenReturn(false);

		assertThatThrownBy(() -> service.login(new LoginRequest("user@example.com", "wrong-password")))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void loginThrowsEmailNotVerifiedWhenEmailIsNotVerified() {
		User user = user();
		ReflectionTestUtils.setField(user, "emailVerified", false);
		UserRepository userRepository = mock(UserRepository.class);
		PasswordHasher passwordHasher = mock(PasswordHasher.class);
		LoginService service = service(userRepository, passwordHasher);
		when(userRepository.findByEmailAndProviderAndDeletedAtIsNull("user@example.com", AuthProvider.email))
			.thenReturn(Optional.of(user));
		when(passwordHasher.matches("Passw@rd123", "hashed-password")).thenReturn(true);

		assertThatThrownBy(() -> service.login(new LoginRequest("user@example.com", "Passw@rd123")))
			.isInstanceOf(EmailNotVerifiedException.class);
	}

	@Test
	void loginThrowsSuspendedUserWhenUserIsSuspended() {
		User user = user();
		ReflectionTestUtils.setField(user, "status", UserStatus.suspended);
		UserRepository userRepository = mock(UserRepository.class);
		PasswordHasher passwordHasher = mock(PasswordHasher.class);
		LoginService service = service(userRepository, passwordHasher);
		when(userRepository.findByEmailAndProviderAndDeletedAtIsNull("user@example.com", AuthProvider.email))
			.thenReturn(Optional.of(user));
		when(passwordHasher.matches("Passw@rd123", "hashed-password")).thenReturn(true);

		assertThatThrownBy(() -> service.login(new LoginRequest("user@example.com", "Passw@rd123")))
			.isInstanceOf(SuspendedUserException.class);
	}

	@Test
	void loginSavesLoginLogAndWritesRedisSessionAfterCommit() {
		User user = user();
		UserRepository userRepository = mock(UserRepository.class);
		PasswordHasher passwordHasher = mock(PasswordHasher.class);
		LoginLogRepository loginLogRepository = mock(LoginLogRepository.class);
		OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
		Sha256TokenHasher tokenHasher = mock(Sha256TokenHasher.class);
		AccessTokenIssuer accessTokenIssuer = mock(AccessTokenIssuer.class);
		RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
		LoginService service = new LoginService(
			userRepository,
			passwordHasher,
			loginLogRepository,
			tokenGenerator,
			tokenHasher,
			accessTokenIssuer,
			sessionStore
		);
		when(userRepository.findByEmailAndProviderAndDeletedAtIsNull("user@example.com", AuthProvider.email))
			.thenReturn(Optional.of(user));
		when(passwordHasher.matches("Passw@rd123", "hashed-password")).thenReturn(true);
		when(tokenGenerator.generate()).thenReturn("sid-1", "refresh-token", "csrf-token");
		when(tokenHasher.hash("refresh-token")).thenReturn("refresh-hash");
		when(accessTokenIssuer.issue(42L, "sid-1", "user@example.com", UserRole.user)).thenReturn("access-token");

		TransactionSynchronizationManager.initSynchronization();
		try {
			LoginResult result = service.login(new LoginRequest("user@example.com", "Passw@rd123"));

			assertThat(result.response().userId()).isEqualTo(42L);
			assertThat(result.response().role()).isEqualTo(UserRole.user);
			assertThat(result.response().passwordResetRequired()).isFalse();
			assertThat(result.accessToken()).isEqualTo("access-token");
			assertThat(result.refreshToken()).isEqualTo("refresh-token");
			assertThat(result.csrfToken()).isEqualTo("csrf-token");
			verify(loginLogRepository).save(any());
			verify(sessionStore, never()).create(any(AuthSession.class));

			for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
				synchronization.afterCommit();
			}

			ArgumentCaptor<AuthSession> sessionCaptor = ArgumentCaptor.forClass(AuthSession.class);
			verify(sessionStore).create(sessionCaptor.capture());
			assertThat(sessionCaptor.getValue().createdAt().getOffset()).isEqualTo(ZoneOffset.UTC);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	private LoginService service(UserRepository userRepository) {
		return service(userRepository, mock(PasswordHasher.class));
	}

	private LoginService service(UserRepository userRepository, PasswordHasher passwordHasher) {
		return new LoginService(
			userRepository,
			passwordHasher,
			mock(LoginLogRepository.class),
			mock(OpaqueTokenGenerator.class),
			mock(Sha256TokenHasher.class),
			mock(AccessTokenIssuer.class),
			mock(RedisAuthSessionStore.class)
		);
	}

	private User user() {
		User user = User.createEmailUser(
			"user@example.com",
			"hashed-password",
			"nickname",
			LocalDate.of(2000, 1, 1),
			GenderType.female,
			"KR"
		);
		ReflectionTestUtils.setField(user, "id", 42L);
		return user;
	}
}
