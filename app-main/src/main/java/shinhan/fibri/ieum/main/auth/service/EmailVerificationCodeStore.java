package shinhan.fibri.ieum.main.auth.service;

import java.time.Duration;
import java.util.Optional;

public interface EmailVerificationCodeStore {

	void saveSignupCode(String email, String codeHash, Duration ttl);

	Optional<String> findSignupCodeHash(String email);

	void deleteSignupCode(String email);

	void saveSignupVerificationToken(String token, String email, Duration ttl);
}
