package shinhan.fibri.ieum.main.auth.service;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.auth.dto.SendEmailVerificationRequest;
import shinhan.fibri.ieum.main.auth.dto.SendEmailVerificationResponse;
import shinhan.fibri.ieum.main.auth.dto.VerifyEmailVerificationRequest;
import shinhan.fibri.ieum.main.auth.dto.VerifyEmailVerificationResponse;
import shinhan.fibri.ieum.main.auth.exception.EmailCodeRateLimitedException;
import shinhan.fibri.ieum.main.auth.exception.EmailDeliveryFailedException;
import shinhan.fibri.ieum.main.auth.exception.EmailTakenException;
import shinhan.fibri.ieum.main.auth.exception.InvalidEmailVerificationCodeException;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailVerificationServiceTest {

	@Test
	void sendSignupCodeNormalizesEmailStoresHashedCodeAndSendsMail() {
		EmailVerificationCodeStore codeStore = mock(EmailVerificationCodeStore.class);
		VerificationMailSender mailSender = mock(VerificationMailSender.class);
		VerificationCodeGenerator codeGenerator = mock(VerificationCodeGenerator.class);
		VerificationCodeHasher codeHasher = mock(VerificationCodeHasher.class);
		EmailVerificationTokenGenerator tokenGenerator = mock(EmailVerificationTokenGenerator.class);
		UserRepository userRepository = mock(UserRepository.class);
		EmailVerificationRateLimiter rateLimiter = mock(EmailVerificationRateLimiter.class);
		EmailVerificationService service = new EmailVerificationService(
			codeStore,
			mailSender,
			codeGenerator,
			codeHasher,
			tokenGenerator,
			userRepository,
			rateLimiter
		);
		when(codeGenerator.generate()).thenReturn("123456");
		when(codeHasher.hash("user@example.com", "123456")).thenReturn("hashed-code");
		when(rateLimiter.tryConsumeSignupSend("user@example.com")).thenReturn(true);
		when(mailSender.sendSignupCode("user@example.com", "123456", 180))
			.thenReturn(CompletableFuture.completedFuture(null));

		SendEmailVerificationResponse response = service.sendSignupCode(
			new SendEmailVerificationRequest(" USER@example.COM ")
		);

		assertThat(response.expiresInSeconds()).isEqualTo(180);
		verify(codeStore).saveSignupCode("user@example.com", "hashed-code", Duration.ofSeconds(180));
		verify(mailSender).sendSignupCode("user@example.com", "123456", 180);
	}

	@Test
	void sendSignupCodeThrowsWhenEmailIsAlreadyTaken() {
		EmailVerificationCodeStore codeStore = mock(EmailVerificationCodeStore.class);
		VerificationMailSender mailSender = mock(VerificationMailSender.class);
		VerificationCodeGenerator codeGenerator = mock(VerificationCodeGenerator.class);
		VerificationCodeHasher codeHasher = mock(VerificationCodeHasher.class);
		EmailVerificationTokenGenerator tokenGenerator = mock(EmailVerificationTokenGenerator.class);
		UserRepository userRepository = mock(UserRepository.class);
		EmailVerificationRateLimiter rateLimiter = mock(EmailVerificationRateLimiter.class);
		EmailVerificationService service = new EmailVerificationService(
			codeStore,
			mailSender,
			codeGenerator,
			codeHasher,
			tokenGenerator,
			userRepository,
			rateLimiter
		);
		when(userRepository.existsByEmailAndProviderAndDeletedAtIsNull("user@example.com", AuthProvider.email))
			.thenReturn(true);

		assertThatThrownBy(() -> service.sendSignupCode(
			new SendEmailVerificationRequest(" USER@example.COM ")
		)).isInstanceOf(EmailTakenException.class);

		verify(rateLimiter, never()).tryConsumeSignupSend("user@example.com");
		verify(codeGenerator, never()).generate();
		verify(codeStore, never()).saveSignupCode("user@example.com", "hashed-code", Duration.ofSeconds(180));
		verify(mailSender, never()).sendSignupCode("user@example.com", "123456", 180);
	}

	@Test
	void sendSignupCodeThrowsWhenRateLimited() {
		EmailVerificationCodeStore codeStore = mock(EmailVerificationCodeStore.class);
		VerificationMailSender mailSender = mock(VerificationMailSender.class);
		VerificationCodeGenerator codeGenerator = mock(VerificationCodeGenerator.class);
		VerificationCodeHasher codeHasher = mock(VerificationCodeHasher.class);
		EmailVerificationTokenGenerator tokenGenerator = mock(EmailVerificationTokenGenerator.class);
		UserRepository userRepository = mock(UserRepository.class);
		EmailVerificationRateLimiter rateLimiter = mock(EmailVerificationRateLimiter.class);
		EmailVerificationService service = new EmailVerificationService(
			codeStore,
			mailSender,
			codeGenerator,
			codeHasher,
			tokenGenerator,
			userRepository,
			rateLimiter
		);
		when(rateLimiter.tryConsumeSignupSend("user@example.com")).thenReturn(false);

		assertThatThrownBy(() -> service.sendSignupCode(
			new SendEmailVerificationRequest(" USER@example.COM ")
		)).isInstanceOf(EmailCodeRateLimitedException.class);

		verify(codeGenerator, never()).generate();
		verify(codeStore, never()).saveSignupCode("user@example.com", "hashed-code", Duration.ofSeconds(180));
		verify(mailSender, never()).sendSignupCode("user@example.com", "123456", 180);
	}

	@Test
	void sendSignupCodeDeletesSavedCodeWhenAsyncMailDeliveryFails() {
		EmailVerificationCodeStore codeStore = mock(EmailVerificationCodeStore.class);
		VerificationMailSender mailSender = mock(VerificationMailSender.class);
		VerificationCodeGenerator codeGenerator = mock(VerificationCodeGenerator.class);
		VerificationCodeHasher codeHasher = mock(VerificationCodeHasher.class);
		EmailVerificationTokenGenerator tokenGenerator = mock(EmailVerificationTokenGenerator.class);
		UserRepository userRepository = mock(UserRepository.class);
		EmailVerificationRateLimiter rateLimiter = mock(EmailVerificationRateLimiter.class);
		EmailVerificationService service = new EmailVerificationService(
			codeStore,
			mailSender,
			codeGenerator,
			codeHasher,
			tokenGenerator,
			userRepository,
			rateLimiter
		);
		when(codeGenerator.generate()).thenReturn("123456");
		when(codeHasher.hash("user@example.com", "123456")).thenReturn("hashed-code");
		when(rateLimiter.tryConsumeSignupSend("user@example.com")).thenReturn(true);
		when(mailSender.sendSignupCode("user@example.com", "123456", 180))
			.thenReturn(CompletableFuture.failedFuture(new RuntimeException("smtp auth failed")));

		SendEmailVerificationResponse response = service.sendSignupCode(
			new SendEmailVerificationRequest(" USER@example.COM ")
		);

		assertThat(response.expiresInSeconds()).isEqualTo(180);
		verify(codeStore).saveSignupCode("user@example.com", "hashed-code", Duration.ofSeconds(180));
		verify(codeStore).deleteSignupCode("user@example.com");
	}

	@Test
	void sendSignupCodeDeletesSavedCodeAndThrowsWhenMailTaskCannotBeSubmitted() {
		EmailVerificationCodeStore codeStore = mock(EmailVerificationCodeStore.class);
		VerificationMailSender mailSender = mock(VerificationMailSender.class);
		VerificationCodeGenerator codeGenerator = mock(VerificationCodeGenerator.class);
		VerificationCodeHasher codeHasher = mock(VerificationCodeHasher.class);
		EmailVerificationTokenGenerator tokenGenerator = mock(EmailVerificationTokenGenerator.class);
		UserRepository userRepository = mock(UserRepository.class);
		EmailVerificationRateLimiter rateLimiter = mock(EmailVerificationRateLimiter.class);
		EmailVerificationService service = new EmailVerificationService(
			codeStore,
			mailSender,
			codeGenerator,
			codeHasher,
			tokenGenerator,
			userRepository,
			rateLimiter
		);
		when(codeGenerator.generate()).thenReturn("123456");
		when(codeHasher.hash("user@example.com", "123456")).thenReturn("hashed-code");
		when(rateLimiter.tryConsumeSignupSend("user@example.com")).thenReturn(true);
		doThrow(new RuntimeException("mail executor rejected"))
			.when(mailSender)
			.sendSignupCode("user@example.com", "123456", 180);

		assertThatThrownBy(() -> service.sendSignupCode(
			new SendEmailVerificationRequest(" USER@example.COM ")
		)).isInstanceOf(EmailDeliveryFailedException.class);

		verify(codeStore).saveSignupCode("user@example.com", "hashed-code", Duration.ofSeconds(180));
		verify(codeStore).deleteSignupCode("user@example.com");
	}

	@Test
	void verifySignupCodeNormalizesEmailDeletesCodeAndStoresVerificationToken() {
		EmailVerificationCodeStore codeStore = mock(EmailVerificationCodeStore.class);
		VerificationMailSender mailSender = mock(VerificationMailSender.class);
		VerificationCodeGenerator codeGenerator = mock(VerificationCodeGenerator.class);
		VerificationCodeHasher codeHasher = mock(VerificationCodeHasher.class);
		EmailVerificationTokenGenerator tokenGenerator = mock(EmailVerificationTokenGenerator.class);
		UserRepository userRepository = mock(UserRepository.class);
		EmailVerificationRateLimiter rateLimiter = mock(EmailVerificationRateLimiter.class);
		EmailVerificationService service = new EmailVerificationService(
			codeStore,
			mailSender,
			codeGenerator,
			codeHasher,
			tokenGenerator,
			userRepository,
			rateLimiter
		);
		when(codeStore.findSignupCodeHash("user@example.com")).thenReturn(Optional.of("hashed-code"));
		when(codeHasher.hash("user@example.com", "123456")).thenReturn("hashed-code");
		when(tokenGenerator.generate()).thenReturn("verification-token");

		VerifyEmailVerificationResponse response = service.verifySignupCode(
			new VerifyEmailVerificationRequest(" USER@example.COM ", "123456")
		);

		assertThat(response.emailVerificationToken()).isEqualTo("verification-token");
		assertThat(response.expiresInSeconds()).isEqualTo(1800);
		verify(codeStore).deleteSignupCode("user@example.com");
		verify(rateLimiter).clearSignupVerifyFailures("user@example.com");
		verify(codeStore).saveSignupVerificationToken(
			"verification-token",
			"user@example.com",
			Duration.ofSeconds(1800)
		);
	}

	@Test
	void verifySignupCodeThrowsWhenCodeIsExpired() {
		EmailVerificationCodeStore codeStore = mock(EmailVerificationCodeStore.class);
		VerificationMailSender mailSender = mock(VerificationMailSender.class);
		VerificationCodeGenerator codeGenerator = mock(VerificationCodeGenerator.class);
		VerificationCodeHasher codeHasher = mock(VerificationCodeHasher.class);
		EmailVerificationTokenGenerator tokenGenerator = mock(EmailVerificationTokenGenerator.class);
		UserRepository userRepository = mock(UserRepository.class);
		EmailVerificationRateLimiter rateLimiter = mock(EmailVerificationRateLimiter.class);
		EmailVerificationService service = new EmailVerificationService(
			codeStore,
			mailSender,
			codeGenerator,
			codeHasher,
			tokenGenerator,
			userRepository,
			rateLimiter
		);
		when(codeStore.findSignupCodeHash("user@example.com")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.verifySignupCode(
			new VerifyEmailVerificationRequest(" USER@example.COM ", "123456")
		)).isInstanceOf(InvalidEmailVerificationCodeException.class);

		verify(codeStore, never()).deleteSignupCode("user@example.com");
		verify(codeStore, never()).saveSignupVerificationToken(
			"verification-token",
			"user@example.com",
			Duration.ofSeconds(1800)
		);
	}

	@Test
	void verifySignupCodeThrowsWhenCodeDoesNotMatch() {
		EmailVerificationCodeStore codeStore = mock(EmailVerificationCodeStore.class);
		VerificationMailSender mailSender = mock(VerificationMailSender.class);
		VerificationCodeGenerator codeGenerator = mock(VerificationCodeGenerator.class);
		VerificationCodeHasher codeHasher = mock(VerificationCodeHasher.class);
		EmailVerificationTokenGenerator tokenGenerator = mock(EmailVerificationTokenGenerator.class);
		UserRepository userRepository = mock(UserRepository.class);
		EmailVerificationRateLimiter rateLimiter = mock(EmailVerificationRateLimiter.class);
		EmailVerificationService service = new EmailVerificationService(
			codeStore,
			mailSender,
			codeGenerator,
			codeHasher,
			tokenGenerator,
			userRepository,
			rateLimiter
		);
		when(codeStore.findSignupCodeHash("user@example.com")).thenReturn(Optional.of("saved-code-hash"));
		when(codeHasher.hash("user@example.com", "000000")).thenReturn("request-code-hash");
		when(rateLimiter.tryConsumeSignupVerifyFailure("user@example.com")).thenReturn(true);

		assertThatThrownBy(() -> service.verifySignupCode(
			new VerifyEmailVerificationRequest(" USER@example.COM ", "000000")
		)).isInstanceOf(InvalidEmailVerificationCodeException.class);

		verify(rateLimiter).tryConsumeSignupVerifyFailure("user@example.com");
		verify(codeStore, never()).deleteSignupCode("user@example.com");
		verify(codeStore, never()).saveSignupVerificationToken(
			"verification-token",
			"user@example.com",
			Duration.ofSeconds(1800)
		);
	}

	@Test
	void verifySignupCodeDeletesCodeWhenFailedAttemptsAreExceeded() {
		EmailVerificationCodeStore codeStore = mock(EmailVerificationCodeStore.class);
		VerificationMailSender mailSender = mock(VerificationMailSender.class);
		VerificationCodeGenerator codeGenerator = mock(VerificationCodeGenerator.class);
		VerificationCodeHasher codeHasher = mock(VerificationCodeHasher.class);
		EmailVerificationTokenGenerator tokenGenerator = mock(EmailVerificationTokenGenerator.class);
		UserRepository userRepository = mock(UserRepository.class);
		EmailVerificationRateLimiter rateLimiter = mock(EmailVerificationRateLimiter.class);
		EmailVerificationService service = new EmailVerificationService(
			codeStore,
			mailSender,
			codeGenerator,
			codeHasher,
			tokenGenerator,
			userRepository,
			rateLimiter
		);
		when(codeStore.findSignupCodeHash("user@example.com")).thenReturn(Optional.of("saved-code-hash"));
		when(codeHasher.hash("user@example.com", "000000")).thenReturn("request-code-hash");
		when(rateLimiter.tryConsumeSignupVerifyFailure("user@example.com")).thenReturn(false);

		assertThatThrownBy(() -> service.verifySignupCode(
			new VerifyEmailVerificationRequest(" USER@example.COM ", "000000")
		)).isInstanceOf(InvalidEmailVerificationCodeException.class);

		verify(rateLimiter).tryConsumeSignupVerifyFailure("user@example.com");
		verify(codeStore).deleteSignupCode("user@example.com");
		verify(codeStore, never()).saveSignupVerificationToken(
			"verification-token",
			"user@example.com",
			Duration.ofSeconds(1800)
		);
	}
}
