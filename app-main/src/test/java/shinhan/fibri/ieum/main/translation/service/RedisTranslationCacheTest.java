package shinhan.fibri.ieum.main.translation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import shinhan.fibri.ieum.main.translation.dto.TranslationResponse;

@SuppressWarnings("unchecked")
class RedisTranslationCacheTest {

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	private final HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
	private final RedisTranslationCache cache = new RedisTranslationCache(redisTemplate);

	@Test
	void storesBothResponseFieldsWithThirtyDayTtl() {
		when(redisTemplate.opsForHash()).thenReturn(hashOperations);

		cache.put(
			TranslationSubjectKind.ANSWER,
			10L,
			TargetLanguage.KO,
			new TranslationResponse("안녕", "en")
		);

		verify(hashOperations).putAll("translation:cache:answer:10:ko", Map.of(
			"translatedText", "안녕",
			"sourceLang", "en"
		));
		verify(redisTemplate).expire("translation:cache:answer:10:ko", Duration.ofDays(30));
	}

	@Test
	void readsOnlyCompleteCachedResponses() {
		when(redisTemplate.opsForHash()).thenReturn(hashOperations);
		when(hashOperations.get("translation:cache:chat_message:99:en", "translatedText")).thenReturn("hello");
		when(hashOperations.get("translation:cache:chat_message:99:en", "sourceLang")).thenReturn("ja");

		assertThat(cache.get(TranslationSubjectKind.CHAT_MESSAGE, 99L, TargetLanguage.EN))
			.contains(new TranslationResponse("hello", "ja"));
	}
}
