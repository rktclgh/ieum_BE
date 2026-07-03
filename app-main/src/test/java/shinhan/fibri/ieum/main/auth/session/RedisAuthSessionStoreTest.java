package shinhan.fibri.ieum.main.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;

@SuppressWarnings("unchecked")
class RedisAuthSessionStoreTest {

	private static final Duration SESSION_TTL = Duration.ofDays(14);

	@Test
	void createStoresSessionRefreshIndexAndUserSessionSet() {
		StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
		HashOperations<String, Object, Object> hashOps = org.mockito.Mockito.mock(HashOperations.class);
		ValueOperations<String, String> valueOps = org.mockito.Mockito.mock(ValueOperations.class);
		SetOperations<String, String> setOps = org.mockito.Mockito.mock(SetOperations.class);
		when(redisTemplate.opsForHash()).thenReturn(hashOps);
		when(redisTemplate.opsForValue()).thenReturn(valueOps);
		when(redisTemplate.opsForSet()).thenReturn(setOps);
		RedisAuthSessionStore store = new RedisAuthSessionStore(redisTemplate);
		AuthSession session = new AuthSession(
			"sid-1",
			42L,
			"refresh-hash",
			null,
			UserRole.user,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-03T00:00:00Z")
		);

		store.create(session);

		verify(hashOps).putAll("auth:session:sid-1", Map.of(
			"userId", "42",
			"refreshTokenHash", "refresh-hash",
			"role", "user",
			"status", "active",
			"createdAt", "2026-07-03T00:00Z"
		));
		verify(redisTemplate).expire("auth:session:sid-1", SESSION_TTL);
		verify(valueOps).set("auth:refresh:refresh-hash", "sid-1", SESSION_TTL);
		verify(setOps).add("auth:user:42:sessions", "sid-1");
		verify(redisTemplate).expire("auth:user:42:sessions", SESSION_TTL);
	}

	@Test
	void rotateRefreshTokenReplacesRefreshIndexAndKeepsPreviousHash() {
		StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
		HashOperations<String, Object, Object> hashOps = org.mockito.Mockito.mock(HashOperations.class);
		ValueOperations<String, String> valueOps = org.mockito.Mockito.mock(ValueOperations.class);
		when(redisTemplate.opsForHash()).thenReturn(hashOps);
		when(redisTemplate.opsForValue()).thenReturn(valueOps);
		RedisAuthSessionStore store = new RedisAuthSessionStore(redisTemplate);
		AuthSession session = new AuthSession(
			"sid-1",
			42L,
			"old-hash",
			null,
			UserRole.user,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-03T00:00:00Z")
		);

		store.rotateRefreshToken(session, "new-hash");

		verify(hashOps).put("auth:session:sid-1", "prevRefreshTokenHash", "old-hash");
		verify(hashOps).put("auth:session:sid-1", "refreshTokenHash", "new-hash");
		verify(redisTemplate).delete("auth:refresh:old-hash");
		verify(valueOps).set("auth:refresh:new-hash", "sid-1", SESSION_TTL);
		verify(redisTemplate).expire("auth:session:sid-1", SESSION_TTL);
		verify(redisTemplate).expire("auth:user:42:sessions", SESSION_TTL);
	}

	@Test
	void findByRefreshTokenHashReturnsSessionFromRefreshIndex() {
		StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
		HashOperations<String, Object, Object> hashOps = org.mockito.Mockito.mock(HashOperations.class);
		ValueOperations<String, String> valueOps = org.mockito.Mockito.mock(ValueOperations.class);
		when(redisTemplate.opsForHash()).thenReturn(hashOps);
		when(redisTemplate.opsForValue()).thenReturn(valueOps);
		when(valueOps.get("auth:refresh:refresh-hash")).thenReturn("sid-1");
		when(hashOps.entries("auth:session:sid-1")).thenReturn(Map.of(
			"userId", "42",
			"refreshTokenHash", "refresh-hash",
			"prevRefreshTokenHash", "previous-hash",
			"role", "user",
			"status", "active",
			"createdAt", "2026-07-03T00:00Z"
		));
		RedisAuthSessionStore store = new RedisAuthSessionStore(redisTemplate);

		Optional<AuthSession> session = store.findByRefreshTokenHash("refresh-hash");

		assertThat(session).hasValue(new AuthSession(
			"sid-1",
			42L,
			"refresh-hash",
			"previous-hash",
			UserRole.user,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-03T00:00Z")
		));
	}

	@Test
	void findByRefreshTokenHashReturnsEmptyWhenRefreshIndexDoesNotExist() {
		StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
		ValueOperations<String, String> valueOps = org.mockito.Mockito.mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOps);
		when(valueOps.get("auth:refresh:missing-hash")).thenReturn(null);
		RedisAuthSessionStore store = new RedisAuthSessionStore(redisTemplate);

		Optional<AuthSession> session = store.findByRefreshTokenHash("missing-hash");

		assertThat(session).isEmpty();
	}

	@Test
	void findByRefreshTokenHashReturnsEmptyWhenSessionDoesNotExist() {
		StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
		HashOperations<String, Object, Object> hashOps = org.mockito.Mockito.mock(HashOperations.class);
		ValueOperations<String, String> valueOps = org.mockito.Mockito.mock(ValueOperations.class);
		when(redisTemplate.opsForHash()).thenReturn(hashOps);
		when(redisTemplate.opsForValue()).thenReturn(valueOps);
		when(valueOps.get("auth:refresh:orphan-hash")).thenReturn("sid-1");
		when(hashOps.entries("auth:session:sid-1")).thenReturn(Map.of());
		RedisAuthSessionStore store = new RedisAuthSessionStore(redisTemplate);

		Optional<AuthSession> session = store.findByRefreshTokenHash("orphan-hash");

		assertThat(session).isEmpty();
	}

	@Test
	void revokeSessionDeletesSessionRefreshIndexesAndUserSessionMembership() {
		StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
		HashOperations<String, Object, Object> hashOps = org.mockito.Mockito.mock(HashOperations.class);
		SetOperations<String, String> setOps = org.mockito.Mockito.mock(SetOperations.class);
		when(redisTemplate.opsForHash()).thenReturn(hashOps);
		when(redisTemplate.opsForSet()).thenReturn(setOps);
		when(hashOps.entries("auth:session:sid-1")).thenReturn(Map.of(
			"userId", "42",
			"refreshTokenHash", "current-hash",
			"prevRefreshTokenHash", "previous-hash"
		));
		RedisAuthSessionStore store = new RedisAuthSessionStore(redisTemplate);

		store.revokeSession("sid-1");

		verify(redisTemplate).delete("auth:session:sid-1");
		verify(redisTemplate).delete("auth:refresh:current-hash");
		verify(redisTemplate).delete("auth:refresh:previous-hash");
		verify(setOps).remove("auth:user:42:sessions", "sid-1");
	}

	@Test
	void revokeAllSessionsOfUserRevokesEverySessionAndDeletesUserSet() {
		StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
		HashOperations<String, Object, Object> hashOps = org.mockito.Mockito.mock(HashOperations.class);
		SetOperations<String, String> setOps = org.mockito.Mockito.mock(SetOperations.class);
		when(redisTemplate.opsForHash()).thenReturn(hashOps);
		when(redisTemplate.opsForSet()).thenReturn(setOps);
		when(setOps.members("auth:user:42:sessions")).thenReturn(Set.of("sid-1", "sid-2"));
		when(hashOps.entries("auth:session:sid-1")).thenReturn(Map.of(
			"userId", "42",
			"refreshTokenHash", "hash-1"
		));
		when(hashOps.entries("auth:session:sid-2")).thenReturn(Map.of(
			"userId", "42",
			"refreshTokenHash", "hash-2"
		));
		RedisAuthSessionStore store = new RedisAuthSessionStore(redisTemplate);

		store.revokeAllSessionsOfUser(42L);

		verify(redisTemplate).delete("auth:session:sid-1");
		verify(redisTemplate).delete("auth:refresh:hash-1");
		verify(redisTemplate).delete("auth:session:sid-2");
		verify(redisTemplate).delete("auth:refresh:hash-2");
		verify(redisTemplate).delete("auth:user:42:sessions");
	}
}
