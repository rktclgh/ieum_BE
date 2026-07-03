package shinhan.fibri.ieum.main.auth.session;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisAuthSessionStore {

	private static final Duration SESSION_TTL = Duration.ofDays(14);

	private final StringRedisTemplate redisTemplate;

	public RedisAuthSessionStore(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	public void create(AuthSession session) {
		redisTemplate.opsForHash().putAll(sessionKey(session.sessionId()), toRedisHash(session));
		redisTemplate.expire(sessionKey(session.sessionId()), SESSION_TTL);
		redisTemplate.opsForValue().set(refreshKey(session.refreshTokenHash()), session.sessionId(), SESSION_TTL);
		redisTemplate.opsForSet().add(userSessionsKey(session.userId()), session.sessionId());
		redisTemplate.expire(userSessionsKey(session.userId()), SESSION_TTL);
	}

	public void rotateRefreshToken(AuthSession session, String newRefreshTokenHash) {
		String sessionKey = sessionKey(session.sessionId());
		redisTemplate.opsForHash().put(sessionKey, "prevRefreshTokenHash", session.refreshTokenHash());
		redisTemplate.opsForHash().put(sessionKey, "refreshTokenHash", newRefreshTokenHash);
		redisTemplate.delete(refreshKey(session.refreshTokenHash()));
		redisTemplate.opsForValue().set(refreshKey(newRefreshTokenHash), session.sessionId(), SESSION_TTL);
		redisTemplate.expire(sessionKey, SESSION_TTL);
		redisTemplate.expire(userSessionsKey(session.userId()), SESSION_TTL);
	}

	public void revokeSession(String sessionId) {
		String sessionKey = sessionKey(sessionId);
		Map<Object, Object> session = redisTemplate.opsForHash().entries(sessionKey);
		redisTemplate.delete(sessionKey);
		deleteRefreshKey(session.get("refreshTokenHash"));
		deleteRefreshKey(session.get("prevRefreshTokenHash"));
		Object userId = session.get("userId");
		if (userId != null) {
			redisTemplate.opsForSet().remove(userSessionsKey(userId.toString()), sessionId);
		}
	}

	public void revokeAllSessionsOfUser(Long userId) {
		String userSessionsKey = userSessionsKey(userId);
		Set<String> sessionIds = redisTemplate.opsForSet().members(userSessionsKey);
		if (sessionIds != null) {
			sessionIds.forEach(this::revokeSession);
		}
		redisTemplate.delete(userSessionsKey);
	}

	private Map<String, String> toRedisHash(AuthSession session) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("userId", String.valueOf(session.userId()));
		values.put("refreshTokenHash", session.refreshTokenHash());
		if (session.prevRefreshTokenHash() != null) {
			values.put("prevRefreshTokenHash", session.prevRefreshTokenHash());
		}
		values.put("role", session.role().name());
		values.put("status", session.status().name());
		values.put("createdAt", session.createdAt().toString());
		return values;
	}

	private void deleteRefreshKey(Object refreshTokenHash) {
		if (refreshTokenHash != null) {
			redisTemplate.delete(refreshKey(refreshTokenHash.toString()));
		}
	}

	private String sessionKey(String sessionId) {
		return "auth:session:" + sessionId;
	}

	private String refreshKey(String refreshTokenHash) {
		return "auth:refresh:" + refreshTokenHash;
	}

	private String userSessionsKey(Long userId) {
		return userSessionsKey(String.valueOf(userId));
	}

	private String userSessionsKey(String userId) {
		return "auth:user:" + userId + ":sessions";
	}
}
