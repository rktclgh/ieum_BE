package shinhan.fibri.ieum.main.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisSocialSignupTokenStore implements SocialSignupTokenStore {

	private static final String SIGNUP_KEY_PREFIX = "auth:social:signup:";

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	public RedisSocialSignupTokenStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	@Override
	public void save(String token, SocialSignupIdentity identity, Duration ttl) {
		redisTemplate.opsForValue().set(SIGNUP_KEY_PREFIX + token, write(identity), ttl);
	}

	@Override
	public Optional<SocialSignupIdentity> find(String token) {
		return Optional.ofNullable(redisTemplate.opsForValue().get(SIGNUP_KEY_PREFIX + token))
			.map(this::read);
	}

	@Override
	public void delete(String token) {
		redisTemplate.delete(SIGNUP_KEY_PREFIX + token);
	}

	private String write(SocialSignupIdentity identity) {
		try {
			return objectMapper.writeValueAsString(identity);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize social signup identity", exception);
		}
	}

	private SocialSignupIdentity read(String payload) {
		try {
			return objectMapper.readValue(payload, SocialSignupIdentity.class);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to deserialize social signup identity", exception);
		}
	}
}
