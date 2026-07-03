package shinhan.fibri.ieum.main.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.main.auth.dto.SendEmailVerificationRequest;
import shinhan.fibri.ieum.main.auth.dto.SendEmailVerificationResponse;
import shinhan.fibri.ieum.main.auth.dto.VerifyEmailVerificationRequest;
import shinhan.fibri.ieum.main.auth.dto.VerifyEmailVerificationResponse;

import java.time.Duration;
import java.util.Locale;

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

	public SendEmailVerificationResponse sendSignupCode(SendEmailVerificationRequest request) {
		String email = normalizeEmail(request.email());
		String code = codeGenerator.generate();
		String codeHash = codeHasher.hash(code);
		codeStore.saveSignupCode(email, codeHash, SIGNUP_CODE_TTL);
		mailSender.sendSignupCode(email, code, SIGNUP_CODE_TTL_SECONDS);
		return new SendEmailVerificationResponse(SIGNUP_CODE_TTL_SECONDS);
	}

	public VerifyEmailVerificationResponse verifySignupCode(VerifyEmailVerificationRequest request) {
		String email = normalizeEmail(request.email());
		String requestCodeHash = codeHasher.hash(request.code());
		String savedCodeHash = codeStore.findSignupCodeHash(email)
			.orElseThrow(() -> new IllegalArgumentException("Invalid email verification code"));
		if (!savedCodeHash.equals(requestCodeHash)) {
			throw new IllegalArgumentException("Invalid email verification code");
		}

		String token = tokenGenerator.generate();
		codeStore.deleteSignupCode(email);
		codeStore.saveSignupVerificationToken(token, email, VERIFICATION_TOKEN_TTL);
		return new VerifyEmailVerificationResponse(token, VERIFICATION_TOKEN_TTL_SECONDS);
	}

	private String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}
}
