package shinhan.fibri.ieum.main.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;

class RedisSocialSignupTokenStoreTest {

	@Test
	void saveStoresIdentityJsonWithSignupKeyAndTtl() throws Exception {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		RedisSocialSignupTokenStore store = new RedisSocialSignupTokenStore(redisTemplate, new ObjectMapper());
		SocialSignupIdentity identity = new SocialSignupIdentity(
			AuthProvider.google,
			"google-sub-123",
			"social@example.com",
			true
		);

		store.save("signup-token", identity, Duration.ofMinutes(30));

		ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
		verify(valueOperations).set(
			eq("auth:social:signup:signup-token"),
			payloadCaptor.capture(),
			eq(Duration.ofMinutes(30))
		);
		assertThat(new ObjectMapper().readValue(payloadCaptor.getValue(), SocialSignupIdentity.class))
			.isEqualTo(identity);
	}

	@Test
	void findReadsIdentityJsonFromSignupKey() throws Exception {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get("auth:social:signup:signup-token")).thenReturn("""
			{"provider":"kakao","providerUid":"kakao-sub-123","email":"social@example.com","emailVerified":false}
			""");
		RedisSocialSignupTokenStore store = new RedisSocialSignupTokenStore(redisTemplate, new ObjectMapper());

		assertThat(store.find("signup-token")).contains(new SocialSignupIdentity(
			AuthProvider.kakao,
			"kakao-sub-123",
			"social@example.com",
			false
		));
	}

	@Test
	void findReturnsEmptyWhenSignupKeyDoesNotExist() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get("auth:social:signup:missing-token")).thenReturn(null);
		RedisSocialSignupTokenStore store = new RedisSocialSignupTokenStore(redisTemplate, new ObjectMapper());

		assertThat(store.find("missing-token")).isEmpty();
	}

	@Test
	void deleteRemovesSignupKey() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		RedisSocialSignupTokenStore store = new RedisSocialSignupTokenStore(redisTemplate, new ObjectMapper());

		store.delete("signup-token");

		verify(redisTemplate).delete("auth:social:signup:signup-token");
	}
}
