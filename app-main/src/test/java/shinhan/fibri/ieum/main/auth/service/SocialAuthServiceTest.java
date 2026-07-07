package shinhan.fibri.ieum.main.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserSettings;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.repository.CountryRepository;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.auth.repository.UserSettingsRepository;
import shinhan.fibri.ieum.main.auth.domain.LoginLog;
import shinhan.fibri.ieum.main.auth.dto.SocialAuthRequest;
import shinhan.fibri.ieum.main.auth.dto.SocialSignupRequest;
import shinhan.fibri.ieum.main.auth.exception.InvalidSignupFieldException;
import shinhan.fibri.ieum.main.auth.exception.InvalidSocialSignupTokenException;
import shinhan.fibri.ieum.main.auth.exception.NicknameTakenException;
import shinhan.fibri.ieum.main.auth.exception.SuspendedUserException;
import shinhan.fibri.ieum.main.auth.repository.LoginLogRepository;
import shinhan.fibri.ieum.main.auth.session.IssuedAuthSession;
import shinhan.fibri.ieum.main.auth.session.OpaqueTokenGenerator;
import shinhan.fibri.ieum.main.auth.session.SessionIssuer;

class SocialAuthServiceTest {

	@Test
	void startLogsInExistingSocialUser() {
		SocialIdentityVerifier identityVerifier = mock(SocialIdentityVerifier.class);
		UserRepository userRepository = mock(UserRepository.class);
		LoginLogRepository loginLogRepository = mock(LoginLogRepository.class);
		SessionIssuer sessionIssuer = mock(SessionIssuer.class);
		SocialSignupTokenStore signupTokenStore = mock(SocialSignupTokenStore.class);
		OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
		SocialAuthService service = new SocialAuthService(
			identityVerifier,
			userRepository,
			loginLogRepository,
			sessionIssuer,
			signupTokenStore,
			tokenGenerator,
			mock(UserSettingsRepository.class),
			mock(CountryRepository.class),
			mock(PasswordHasher.class)
		);
		SocialAuthRequest request = new SocialAuthRequest("google", "id-token", null, null);
		User user = socialUser(AuthProvider.google, "google-sub-123");
		when(identityVerifier.verify(request)).thenReturn(new VerifiedSocialIdentity(
			AuthProvider.google,
			"google-sub-123",
			"social@example.com",
			true
		));
		when(userRepository.findByProviderAndProviderUidAndDeletedAtIsNull(AuthProvider.google, "google-sub-123"))
			.thenReturn(Optional.of(user));
		when(sessionIssuer.issue(user)).thenReturn(new IssuedAuthSession("access-token", "refresh-token", "csrf-token"));

		SocialAuthResult result = service.start(request);

		assertThat(result.response().isNewUser()).isFalse();
		assertThat(result.response().userId()).isEqualTo(42L);
		assertThat(result.response().role()).isEqualTo(UserRole.user);
		assertThat(result.accessToken()).isEqualTo("access-token");
		assertThat(result.refreshToken()).isEqualTo("refresh-token");
		assertThat(result.csrfToken()).isEqualTo("csrf-token");
		verify(loginLogRepository).save(any(LoginLog.class));
		verify(signupTokenStore, never()).save(any(), any(), any());
	}

	@Test
	void startIssuesSignupTokenForNewSocialIdentityWithoutCreatingUser() {
		SocialIdentityVerifier identityVerifier = mock(SocialIdentityVerifier.class);
		UserRepository userRepository = mock(UserRepository.class);
		LoginLogRepository loginLogRepository = mock(LoginLogRepository.class);
		SessionIssuer sessionIssuer = mock(SessionIssuer.class);
		SocialSignupTokenStore signupTokenStore = mock(SocialSignupTokenStore.class);
		OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
		SocialAuthService service = new SocialAuthService(
			identityVerifier,
			userRepository,
			loginLogRepository,
			sessionIssuer,
			signupTokenStore,
			tokenGenerator,
			mock(UserSettingsRepository.class),
			mock(CountryRepository.class),
			mock(PasswordHasher.class)
		);
		SocialAuthRequest request = new SocialAuthRequest("google", "id-token", null, null);
		VerifiedSocialIdentity identity = new VerifiedSocialIdentity(
			AuthProvider.google,
			"google-sub-123",
			"social@example.com",
			true
		);
		when(identityVerifier.verify(request)).thenReturn(identity);
		when(userRepository.findByProviderAndProviderUidAndDeletedAtIsNull(AuthProvider.google, "google-sub-123"))
			.thenReturn(Optional.empty());
		when(tokenGenerator.generate()).thenReturn("signup-token");

		SocialAuthResult result = service.start(request);

		assertThat(result.response().isNewUser()).isTrue();
		assertThat(result.response().socialSignupToken()).isEqualTo("signup-token");
		assertThat(result.response().expiresInSeconds()).isEqualTo(1800);
		assertThat(result.accessToken()).isNull();
		assertThat(result.refreshToken()).isNull();
		assertThat(result.csrfToken()).isNull();
		verify(signupTokenStore).save(
			"signup-token",
			new SocialSignupIdentity(AuthProvider.google, "google-sub-123", "social@example.com", true),
			Duration.ofMinutes(30)
		);
		verify(loginLogRepository, never()).save(any());
		verify(sessionIssuer, never()).issue(any());
	}

	@Test
	void startRejectsSuspendedSocialUser() {
		SocialIdentityVerifier identityVerifier = mock(SocialIdentityVerifier.class);
		UserRepository userRepository = mock(UserRepository.class);
		LoginLogRepository loginLogRepository = mock(LoginLogRepository.class);
		SessionIssuer sessionIssuer = mock(SessionIssuer.class);
		SocialSignupTokenStore signupTokenStore = mock(SocialSignupTokenStore.class);
		OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
		SocialAuthService service = new SocialAuthService(
			identityVerifier,
			userRepository,
			loginLogRepository,
			sessionIssuer,
			signupTokenStore,
			tokenGenerator,
			mock(UserSettingsRepository.class),
			mock(CountryRepository.class),
			mock(PasswordHasher.class)
		);
		SocialAuthRequest request = new SocialAuthRequest("google", "id-token", null, null);
		User user = socialUser(AuthProvider.google, "google-sub-123");
		ReflectionTestUtils.setField(user, "status", UserStatus.suspended);
		when(identityVerifier.verify(request)).thenReturn(new VerifiedSocialIdentity(
			AuthProvider.google,
			"google-sub-123",
			"social@example.com",
			true
		));
		when(userRepository.findByProviderAndProviderUidAndDeletedAtIsNull(AuthProvider.google, "google-sub-123"))
			.thenReturn(Optional.of(user));

		assertThatThrownBy(() -> service.start(request))
			.isInstanceOf(SuspendedUserException.class);
		verify(loginLogRepository, never()).save(any());
		verify(sessionIssuer, never()).issue(any());
		verify(signupTokenStore, never()).save(any(), any(), any());
	}

	@Test
	void signupCreatesSocialUserAndIssuesSession() {
		SocialIdentityVerifier identityVerifier = mock(SocialIdentityVerifier.class);
		UserRepository userRepository = mock(UserRepository.class);
		LoginLogRepository loginLogRepository = mock(LoginLogRepository.class);
		SessionIssuer sessionIssuer = mock(SessionIssuer.class);
		SocialSignupTokenStore signupTokenStore = mock(SocialSignupTokenStore.class);
		OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
		UserSettingsRepository userSettingsRepository = mock(UserSettingsRepository.class);
		CountryRepository countryRepository = mock(CountryRepository.class);
		PasswordHasher passwordHasher = mock(PasswordHasher.class);
		SocialAuthService service = new SocialAuthService(
			identityVerifier,
			userRepository,
			loginLogRepository,
			sessionIssuer,
			signupTokenStore,
			tokenGenerator,
			userSettingsRepository,
			countryRepository,
			passwordHasher
		);
		when(signupTokenStore.find("signup-token")).thenReturn(Optional.of(new SocialSignupIdentity(
			AuthProvider.google,
			"google-sub-123",
			"social@example.com",
			true
		)));
		when(countryRepository.existsByCodeAndIsActiveTrue("KR")).thenReturn(true);
		when(tokenGenerator.generate()).thenReturn("random-password");
		when(passwordHasher.hash("random-password")).thenReturn("hashed-random-password");
		User savedUser = socialUser(AuthProvider.google, "google-sub-123");
		when(userRepository.save(any(User.class))).thenReturn(savedUser);
		when(sessionIssuer.issue(savedUser)).thenReturn(new IssuedAuthSession("access-token", "refresh-token", "csrf-token"));

		SocialSignupResult result = service.signup(validSocialSignupRequest());

		assertThat(result.response().userId()).isEqualTo(42L);
		assertThat(result.response().role()).isEqualTo(UserRole.user);
		assertThat(result.accessToken()).isEqualTo("access-token");
		assertThat(result.refreshToken()).isEqualTo("refresh-token");
		assertThat(result.csrfToken()).isEqualTo("csrf-token");
		verify(userRepository).save(any(User.class));
		verify(userSettingsRepository).save(any(UserSettings.class));
		verify(loginLogRepository).save(any(LoginLog.class));
		verify(signupTokenStore).delete("signup-token");
	}

	@Test
	void signupRejectsInvalidSignupToken() {
		SocialSignupTokenStore signupTokenStore = mock(SocialSignupTokenStore.class);
		SocialAuthService service = new SocialAuthService(
			mock(SocialIdentityVerifier.class),
			mock(UserRepository.class),
			mock(LoginLogRepository.class),
			mock(SessionIssuer.class),
			signupTokenStore,
			mock(OpaqueTokenGenerator.class),
			mock(UserSettingsRepository.class),
			mock(CountryRepository.class),
			mock(PasswordHasher.class)
		);
		when(signupTokenStore.find("signup-token")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.signup(validSocialSignupRequest()))
			.isInstanceOf(InvalidSocialSignupTokenException.class);
	}

	@Test
	void signupRejectsTakenNickname() {
		UserRepository userRepository = mock(UserRepository.class);
		SocialSignupTokenStore signupTokenStore = mock(SocialSignupTokenStore.class);
		SocialAuthService service = new SocialAuthService(
			mock(SocialIdentityVerifier.class),
			userRepository,
			mock(LoginLogRepository.class),
			mock(SessionIssuer.class),
			signupTokenStore,
			mock(OpaqueTokenGenerator.class),
			mock(UserSettingsRepository.class),
			mock(CountryRepository.class),
			mock(PasswordHasher.class)
		);
		when(signupTokenStore.find("signup-token")).thenReturn(Optional.of(new SocialSignupIdentity(
			AuthProvider.google,
			"google-sub-123",
			"social@example.com",
			true
		)));
		when(userRepository.existsByNicknameAndDeletedAtIsNull("nickname")).thenReturn(true);

		assertThatThrownBy(() -> service.signup(validSocialSignupRequest()))
			.isInstanceOf(NicknameTakenException.class);
	}

	@Test
	void signupRejectsUnsupportedNationality() {
		CountryRepository countryRepository = mock(CountryRepository.class);
		SocialSignupTokenStore signupTokenStore = mock(SocialSignupTokenStore.class);
		SocialAuthService service = new SocialAuthService(
			mock(SocialIdentityVerifier.class),
			mock(UserRepository.class),
			mock(LoginLogRepository.class),
			mock(SessionIssuer.class),
			signupTokenStore,
			mock(OpaqueTokenGenerator.class),
			mock(UserSettingsRepository.class),
			countryRepository,
			mock(PasswordHasher.class)
		);
		when(signupTokenStore.find("signup-token")).thenReturn(Optional.of(new SocialSignupIdentity(
			AuthProvider.google,
			"google-sub-123",
			"social@example.com",
			true
		)));
		when(countryRepository.existsByCodeAndIsActiveTrue("KR")).thenReturn(false);

		assertThatThrownBy(() -> service.signup(validSocialSignupRequest()))
			.isInstanceOfSatisfying(InvalidSignupFieldException.class, exception ->
				assertThat(exception.field()).isEqualTo("nationality")
			);
	}

	private User socialUser(AuthProvider provider, String providerUid) {
		User user = User.createSocialUser(
			provider,
			providerUid,
			"social@example.com",
			true,
			"hash",
			"nickname",
			LocalDate.of(2000, 1, 1),
			GenderType.female,
			"KR"
		);
		ReflectionTestUtils.setField(user, "id", 42L);
		return user;
	}

	private SocialSignupRequest validSocialSignupRequest() {
		return new SocialSignupRequest(
			"signup-token",
			"nickname",
			LocalDate.of(2000, 1, 1),
			"female",
			"KR",
			"ko"
		);
	}
}
