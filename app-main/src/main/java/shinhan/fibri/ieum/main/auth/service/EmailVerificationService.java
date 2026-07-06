package shinhan.fibri.ieum.main.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.auth.validation.AuthEmailNormalizer;
import shinhan.fibri.ieum.main.auth.dto.SendEmailVerificationRequest;
import shinhan.fibri.ieum.main.auth.dto.SendEmailVerificationResponse;
import shinhan.fibri.ieum.main.auth.dto.VerifyEmailVerificationRequest;
import shinhan.fibri.ieum.main.auth.dto.VerifyEmailVerificationResponse;
import shinhan.fibri.ieum.main.auth.exception.EmailCodeRateLimitedException;
import shinhan.fibri.ieum.main.auth.exception.EmailDeliveryFailedException;
import shinhan.fibri.ieum.main.auth.exception.EmailTakenException;
import shinhan.fibri.ieum.main.auth.exception.InvalidEmailVerificationCodeException;

import java.time.Duration;
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

	private static final int SIGNUP_CODE_TTL_SECONDS = 180;
	private static final Duration SIGNUP_CODE_TTL = Duration.ofSeconds(SIGNUP_CODE_TTL_SECONDS);
	private static final int VERIFICATION_TOKEN_TTL_SECONDS = 1800;
	private static final Duration VERIFICATION_TOKEN_TTL = Duration.ofSeconds(VERIFICATION_TOKEN_TTL_SECONDS);

	private final EmailVerificationCodeStore codeStore;
	private final VerificationMailSender mailSender;
	private final VerificationCodeGenerator codeGenerator;
	private final VerificationCodeHasher codeHasher;
	private final EmailVerificationTokenGenerator tokenGenerator;
	private final UserRepository userRepository;
	private final EmailVerificationRateLimiter rateLimiter;

	public SendEmailVerificationResponse sendSignupCode(SendEmailVerificationRequest request) {
		String email = AuthEmailNormalizer.normalize(request.email());
		if (userRepository.existsByEmailAndProviderAndDeletedAtIsNull(email, AuthProvider.email)) {
			throw new EmailTakenException();
		}
		if (!rateLimiter.tryConsumeSignupSend(email)) {
			throw new EmailCodeRateLimitedException();
		}
		String code = codeGenerator.generate();
		String codeHash = codeHasher.hash(email, code);
		codeStore.saveSignupCode(email, codeHash, SIGNUP_CODE_TTL);
		try {
			mailSender.sendSignupCode(email, code, SIGNUP_CODE_TTL_SECONDS);
		} catch (RuntimeException exception) {
			codeStore.deleteSignupCode(email);
			throw new EmailDeliveryFailedException(exception);
		}
		return new SendEmailVerificationResponse(SIGNUP_CODE_TTL_SECONDS);
	}

	public VerifyEmailVerificationResponse verifySignupCode(VerifyEmailVerificationRequest request) {
		String email = AuthEmailNormalizer.normalize(request.email());
		String requestCodeHash = codeHasher.hash(email, request.code());
		String savedCodeHash = codeStore.findSignupCodeHash(email)
			.orElseThrow(InvalidEmailVerificationCodeException::new);
		if (!savedCodeHash.equals(requestCodeHash)) {
			if (!rateLimiter.tryConsumeSignupVerifyFailure(email)) {
				codeStore.deleteSignupCode(email);
			}
			throw new InvalidEmailVerificationCodeException();
		}

		String token = tokenGenerator.generate();
		codeStore.deleteSignupCode(email);
		rateLimiter.clearSignupVerifyFailures(email);
		codeStore.saveSignupVerificationToken(token, email, VERIFICATION_TOKEN_TTL);
		return new VerifyEmailVerificationResponse(token, VERIFICATION_TOKEN_TTL_SECONDS);
	}

}
