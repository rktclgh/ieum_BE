package shinhan.fibri.ieum.main.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RedisEmailVerificationCodeStore implements EmailVerificationCodeStore {

	private static final String SIGNUP_CODE_KEY_PREFIX = "auth:email:signup:code:";
	private static final String SIGNUP_VERIFIED_KEY_PREFIX = "auth:email:signup:verified:";

	private final StringRedisTemplate redisTemplate;

	@Override
	public void saveSignupCode(String email, String codeHash, Duration ttl) {
		redisTemplate.opsForValue().set(SIGNUP_CODE_KEY_PREFIX + email, codeHash, ttl);
	}

	@Override
	public Optional<String> findSignupCodeHash(String email) {
		return Optional.ofNullable(redisTemplate.opsForValue().get(SIGNUP_CODE_KEY_PREFIX + email));
	}

	@Override
	public void deleteSignupCode(String email) {
		redisTemplate.delete(SIGNUP_CODE_KEY_PREFIX + email);
	}

	@Override
	public void saveSignupVerificationToken(String token, String email, Duration ttl) {
		redisTemplate.opsForValue().set(SIGNUP_VERIFIED_KEY_PREFIX + token, email, ttl);
	}
}
