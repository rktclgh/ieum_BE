package shinhan.fibri.ieum.main.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class RedisEmailVerificationCodeStoreTest {

	@Test
	void saveSignupCodeStoresHashWithSignupKeyAndTtl() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		RedisEmailVerificationCodeStore store = new RedisEmailVerificationCodeStore(redisTemplate);

		store.saveSignupCode("user@example.com", "hashed-code", Duration.ofSeconds(180));

		verify(valueOperations).set(
			"auth:email:signup:code:user@example.com",
			"hashed-code",
			Duration.ofSeconds(180)
		);
	}

	@Test
	void findSignupCodeHashReadsSignupCodeKey() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get("auth:email:signup:code:user@example.com")).thenReturn("hashed-code");
		RedisEmailVerificationCodeStore store = new RedisEmailVerificationCodeStore(redisTemplate);

		assertThat(store.findSignupCodeHash("user@example.com")).contains("hashed-code");
	}

	@Test
	void deleteSignupCodeDeletesSignupCodeKey() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		RedisEmailVerificationCodeStore store = new RedisEmailVerificationCodeStore(redisTemplate);

		store.deleteSignupCode("user@example.com");

		verify(redisTemplate).delete("auth:email:signup:code:user@example.com");
	}

	@Test
	void saveSignupVerificationTokenStoresEmailWithVerifiedKeyAndTtl() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		RedisEmailVerificationCodeStore store = new RedisEmailVerificationCodeStore(redisTemplate);

		store.saveSignupVerificationToken("verification-token", "user@example.com", Duration.ofMinutes(30));

		verify(valueOperations).set(
			"auth:email:signup:verified:verification-token",
			"user@example.com",
			Duration.ofMinutes(30)
		);
	}

	@Test
	void findSignupVerificationEmailReadsVerifiedKey() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get("auth:email:signup:verified:verification-token"))
			.thenReturn("user@example.com");
		RedisEmailVerificationCodeStore store = new RedisEmailVerificationCodeStore(redisTemplate);

		assertThat(store.findSignupVerificationEmail("verification-token")).contains("user@example.com");
	}

	@Test
	void deleteSignupVerificationTokenDeletesVerifiedKey() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		RedisEmailVerificationCodeStore store = new RedisEmailVerificationCodeStore(redisTemplate);

		store.deleteSignupVerificationToken("verification-token");

		verify(redisTemplate).delete("auth:email:signup:verified:verification-token");
	}
}
