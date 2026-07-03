package shinhan.fibri.ieum.main.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserSettings;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.auth.repository.UserSettingsRepository;
import shinhan.fibri.ieum.main.auth.dto.SignupRequest;
import shinhan.fibri.ieum.main.auth.dto.SignupResponse;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignupServiceTest {

	@Test
	void signupConsumesVerificationTokenAndCreatesEmailUser() {
		EmailVerificationCodeStore codeStore = mock(EmailVerificationCodeStore.class);
		UserRepository userRepository = mock(UserRepository.class);
		UserSettingsRepository userSettingsRepository = mock(UserSettingsRepository.class);
		PasswordHasher passwordHasher = mock(PasswordHasher.class);
		SignupService service = new SignupService(
			codeStore,
			userRepository,
			userSettingsRepository,
			passwordHasher
		);
		when(codeStore.findSignupVerificationEmail("verification-token"))
			.thenReturn(Optional.of("user@example.com"));
		when(passwordHasher.hash("password123")).thenReturn("hashed-password");
		User savedUser = User.createEmailUser(
			"user@example.com",
			"hashed-password",
			"nickname",
			LocalDate.of(2000, 1, 1)
		);
		ReflectionTestUtils.setField(savedUser, "id", 42L);
		when(userRepository.save(any(User.class))).thenReturn(savedUser);

		SignupResponse response = service.signup(new SignupRequest(
			" USER@example.COM ",
			"password123",
			"nickname",
			LocalDate.of(2000, 1, 1),
			"verification-token"
		));

		assertThat(response.userId()).isEqualTo(42L);
		verify(codeStore).findSignupVerificationEmail("verification-token");
		verify(passwordHasher).hash("password123");
		verify(userRepository).save(any(User.class));
		verify(userSettingsRepository).save(any(UserSettings.class));
		verify(codeStore).deleteSignupVerificationToken("verification-token");
	}

	@Test
	void signupDeletesVerificationTokenAfterCommitWhenTransactionSynchronizationIsActive() {
		EmailVerificationCodeStore codeStore = mock(EmailVerificationCodeStore.class);
		UserRepository userRepository = mock(UserRepository.class);
		UserSettingsRepository userSettingsRepository = mock(UserSettingsRepository.class);
		PasswordHasher passwordHasher = mock(PasswordHasher.class);
		SignupService service = new SignupService(
			codeStore,
			userRepository,
			userSettingsRepository,
			passwordHasher
		);
		when(codeStore.findSignupVerificationEmail("verification-token"))
			.thenReturn(Optional.of("user@example.com"));
		when(passwordHasher.hash("password123")).thenReturn("hashed-password");
		User savedUser = User.createEmailUser(
			"user@example.com",
			"hashed-password",
			"nickname",
			LocalDate.of(2000, 1, 1)
		);
		ReflectionTestUtils.setField(savedUser, "id", 42L);
		when(userRepository.save(any(User.class))).thenReturn(savedUser);

		TransactionSynchronizationManager.initSynchronization();
		try {
			service.signup(new SignupRequest(
				"user@example.com",
				"password123",
				"nickname",
				LocalDate.of(2000, 1, 1),
				"verification-token"
			));

			verify(codeStore, never()).deleteSignupVerificationToken("verification-token");
			for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
				synchronization.afterCommit();
			}
			verify(codeStore).deleteSignupVerificationToken("verification-token");
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}
}
