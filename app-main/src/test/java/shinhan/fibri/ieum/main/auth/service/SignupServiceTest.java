package shinhan.fibri.ieum.main.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserSettings;
import shinhan.fibri.ieum.common.auth.repository.CountryRepository;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.auth.repository.UserSettingsRepository;
import shinhan.fibri.ieum.main.auth.dto.SignupRequest;
import shinhan.fibri.ieum.main.auth.dto.SignupResponse;
import shinhan.fibri.ieum.main.auth.exception.EmailTakenException;
import shinhan.fibri.ieum.main.auth.exception.InvalidEmailVerificationTokenException;
import shinhan.fibri.ieum.main.auth.exception.InvalidSignupFieldException;
import shinhan.fibri.ieum.main.auth.exception.NicknameTakenException;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignupServiceTest {

	private EmailVerificationCodeStore codeStore;
	private UserRepository userRepository;
	private UserSettingsRepository userSettingsRepository;
	private CountryRepository countryRepository;
	private PasswordHasher passwordHasher;
	private SignupService service;

	@BeforeEach
	void setUp() {
		codeStore = mock(EmailVerificationCodeStore.class);
		userRepository = mock(UserRepository.class);
		userSettingsRepository = mock(UserSettingsRepository.class);
		countryRepository = mock(CountryRepository.class);
		passwordHasher = mock(PasswordHasher.class);
		service = new SignupService(
			codeStore,
			userRepository,
			userSettingsRepository,
			countryRepository,
			passwordHasher
		);
	}

	@Test
	void signupConsumesVerificationTokenAndCreatesEmailUser() {
		allowValidSignup();
		User savedUser = savedUser();
		when(userRepository.save(any(User.class))).thenReturn(savedUser);

		SignupResponse response = service.signup(validRequest());

		assertThat(response.userId()).isEqualTo(42L);
		ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
		ArgumentCaptor<UserSettings> userSettingsCaptor = ArgumentCaptor.forClass(UserSettings.class);
		verify(userRepository).save(userCaptor.capture());
		verify(userSettingsRepository).save(userSettingsCaptor.capture());
		User user = userCaptor.getValue();
		assertThat(user.getEmail()).isEqualTo("user@example.com");
		assertThat(user.getPasswordHash()).isEqualTo("hashed-password");
		assertThat(user.getGender()).isEqualTo(GenderType.female);
		assertThat(user.getNationality()).isEqualTo("KR");
		assertThat(userSettingsCaptor.getValue().getLanguage()).isEqualTo("ko");
		verify(codeStore).findSignupVerificationEmail("verification-token");
		verify(codeStore).deleteSignupVerificationToken("verification-token");
	}

	@Test
	void signupDeletesVerificationTokenAfterCommitWhenTransactionSynchronizationIsActive() {
		allowValidSignup();
		when(userRepository.save(any(User.class))).thenReturn(savedUser());

		TransactionSynchronizationManager.initSynchronization();
		try {
			service.signup(validRequest());

			verify(codeStore, never()).deleteSignupVerificationToken("verification-token");
			for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
				synchronization.afterCommit();
			}
			verify(codeStore).deleteSignupVerificationToken("verification-token");
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void signupThrowsWhenVerificationTokenIsExpired() {
		when(codeStore.findSignupVerificationEmail("expired-token")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.signup(validRequest("expired-token")))
			.isInstanceOf(InvalidEmailVerificationTokenException.class);

		verify(userRepository, never()).save(any(User.class));
		verify(userSettingsRepository, never()).save(any(UserSettings.class));
		verify(codeStore, never()).deleteSignupVerificationToken("expired-token");
	}

	@Test
	void signupThrowsWhenVerificationTokenEmailDoesNotMatchRequestEmail() {
		when(codeStore.findSignupVerificationEmail("verification-token"))
			.thenReturn(Optional.of("other@example.com"));

		assertThatThrownBy(() -> service.signup(validRequest()))
			.isInstanceOf(InvalidEmailVerificationTokenException.class);

		verify(userRepository, never()).save(any(User.class));
		verify(userSettingsRepository, never()).save(any(UserSettings.class));
		verify(codeStore, never()).deleteSignupVerificationToken("verification-token");
	}

	@Test
	void signupThrowsWhenEmailAlreadyExists() {
		when(codeStore.findSignupVerificationEmail("verification-token"))
			.thenReturn(Optional.of("user@example.com"));
		when(userRepository.existsByEmailAndProviderAndDeletedAtIsNull("user@example.com", AuthProvider.email))
			.thenReturn(true);

		assertThatThrownBy(() -> service.signup(validRequest()))
			.isInstanceOf(EmailTakenException.class);

		verify(passwordHasher, never()).hash("password123");
		verify(userRepository, never()).save(any(User.class));
		verify(userSettingsRepository, never()).save(any(UserSettings.class));
		verify(codeStore, never()).deleteSignupVerificationToken("verification-token");
	}

	@Test
	void signupThrowsWhenNicknameAlreadyExists() {
		when(codeStore.findSignupVerificationEmail("verification-token"))
			.thenReturn(Optional.of("user@example.com"));
		when(userRepository.existsByNicknameAndDeletedAtIsNull("nickname")).thenReturn(true);

		assertThatThrownBy(() -> service.signup(validRequest()))
			.isInstanceOf(NicknameTakenException.class);

		verify(passwordHasher, never()).hash("password123");
		verify(userRepository, never()).save(any(User.class));
		verify(userSettingsRepository, never()).save(any(UserSettings.class));
		verify(codeStore, never()).deleteSignupVerificationToken("verification-token");
	}

	@Test
	void signupThrowsWhenNationalityIsNotActiveCountryCode() {
		when(codeStore.findSignupVerificationEmail("verification-token"))
			.thenReturn(Optional.of("user@example.com"));
		when(countryRepository.existsByCodeAndIsActiveTrue("ZZ")).thenReturn(false);

		assertThatThrownBy(() -> service.signup(validRequestWithNationality("ZZ")))
			.isInstanceOfSatisfying(InvalidSignupFieldException.class, exception -> {
				assertThat(exception.field()).isEqualTo("nationality");
				assertThat(exception.getMessage()).isEqualTo("Nationality is not supported");
			});

		verify(passwordHasher, never()).hash("password123");
		verify(userRepository, never()).save(any(User.class));
		verify(userSettingsRepository, never()).save(any(UserSettings.class));
		verify(codeStore, never()).deleteSignupVerificationToken("verification-token");
	}

	@Test
	void signupThrowsWhenGenderIsNotSupported() {
		when(codeStore.findSignupVerificationEmail("verification-token"))
			.thenReturn(Optional.of("user@example.com"));

		assertThatThrownBy(() -> service.signup(validRequestWithGender("unknown")))
			.isInstanceOfSatisfying(InvalidSignupFieldException.class, exception -> {
				assertThat(exception.field()).isEqualTo("gender");
				assertThat(exception.getMessage()).isEqualTo("Gender is not supported");
			});

		verify(countryRepository, never()).existsByCodeAndIsActiveTrue(any());
		verify(passwordHasher, never()).hash("password123");
		verify(userRepository, never()).save(any(User.class));
		verify(userSettingsRepository, never()).save(any(UserSettings.class));
		verify(codeStore, never()).deleteSignupVerificationToken("verification-token");
	}

	@Test
	void signupThrowsWhenLanguageIsNotSupported() {
		when(codeStore.findSignupVerificationEmail("verification-token"))
			.thenReturn(Optional.of("user@example.com"));
		when(countryRepository.existsByCodeAndIsActiveTrue("KR")).thenReturn(true);

		assertThatThrownBy(() -> service.signup(validRequestWithLanguage("de")))
			.isInstanceOfSatisfying(InvalidSignupFieldException.class, exception -> {
				assertThat(exception.field()).isEqualTo("language");
				assertThat(exception.getMessage()).isEqualTo("Language is not supported");
			});

		verify(passwordHasher, never()).hash("password123");
		verify(userRepository, never()).save(any(User.class));
		verify(userSettingsRepository, never()).save(any(UserSettings.class));
		verify(codeStore, never()).deleteSignupVerificationToken("verification-token");
	}

	@Test
	void isEmailAvailableNormalizesEmailAndChecksEmailProviderUser() {
		when(userRepository.existsByEmailAndProviderAndDeletedAtIsNull("user@example.com", AuthProvider.email))
			.thenReturn(false);

		assertThat(service.isEmailAvailable(" USER@example.COM ")).isTrue();
	}

	@Test
	void isNicknameAvailableChecksActiveNickname() {
		when(userRepository.existsByNicknameAndDeletedAtIsNull("nickname")).thenReturn(true);

		assertThat(service.isNicknameAvailable("nickname")).isFalse();
	}

	@Test
	void signupMapsEmailUniqueConstraintViolationToEmailTakenException() {
		allowValidSignup();
		when(userRepository.save(any(User.class)))
			.thenThrow(new DataIntegrityViolationException("uidx_users_email_provider"));

		assertThatThrownBy(() -> service.signup(validRequest()))
			.isInstanceOf(EmailTakenException.class);

		verify(userSettingsRepository, never()).save(any(UserSettings.class));
		verify(codeStore, never()).deleteSignupVerificationToken("verification-token");
	}

	@Test
	void signupMapsNicknameUniqueConstraintViolationToNicknameTakenException() {
		allowValidSignup();
		when(userRepository.save(any(User.class)))
			.thenThrow(new DataIntegrityViolationException("uidx_users_nickname"));

		assertThatThrownBy(() -> service.signup(validRequest()))
			.isInstanceOf(NicknameTakenException.class);

		verify(userSettingsRepository, never()).save(any(UserSettings.class));
		verify(codeStore, never()).deleteSignupVerificationToken("verification-token");
	}

	private void allowValidSignup() {
		when(codeStore.findSignupVerificationEmail("verification-token"))
			.thenReturn(Optional.of("user@example.com"));
		when(countryRepository.existsByCodeAndIsActiveTrue("KR")).thenReturn(true);
		when(passwordHasher.hash("password123")).thenReturn("hashed-password");
	}

	private User savedUser() {
		User savedUser = User.createEmailUser(
			"user@example.com",
			"hashed-password",
			"nickname",
			LocalDate.of(2000, 1, 1),
			GenderType.female,
			"KR"
		);
		ReflectionTestUtils.setField(savedUser, "id", 42L);
		return savedUser;
	}

	private SignupRequest validRequest() {
		return new SignupRequest(
			" USER@example.COM ",
			"password123",
			"nickname",
			LocalDate.of(2000, 1, 1),
			"female",
			"KR",
			"ko",
			"verification-token"
		);
	}

	private SignupRequest validRequest(String emailVerificationToken) {
		return new SignupRequest(
			"user@example.com",
			"password123",
			"nickname",
			LocalDate.of(2000, 1, 1),
			"female",
			"KR",
			"ko",
			emailVerificationToken
		);
	}

	private SignupRequest validRequestWithNationality(String nationality) {
		return new SignupRequest(
			"user@example.com",
			"password123",
			"nickname",
			LocalDate.of(2000, 1, 1),
			"female",
			nationality,
			"ko",
			"verification-token"
		);
	}

	private SignupRequest validRequestWithGender(String gender) {
		return new SignupRequest(
			"user@example.com",
			"password123",
			"nickname",
			LocalDate.of(2000, 1, 1),
			gender,
			"KR",
			"ko",
			"verification-token"
		);
	}

	private SignupRequest validRequestWithLanguage(String language) {
		return new SignupRequest(
			"user@example.com",
			"password123",
			"nickname",
			LocalDate.of(2000, 1, 1),
			"female",
			"KR",
			language,
			"verification-token"
		);
	}
}
