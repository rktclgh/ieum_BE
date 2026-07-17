package shinhan.fibri.ieum.main.translation.service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.main.translation.dto.TranslationResponse;

@Component
@RequiredArgsConstructor
public class RedisTranslationCache implements TranslationCache {

	private static final Duration CACHE_TTL = Duration.ofDays(30);
	private static final String TRANSLATED_TEXT_FIELD = "translatedText";
	private static final String SOURCE_LANG_FIELD = "sourceLang";

	private final StringRedisTemplate redisTemplate;

	@Override
	public Optional<TranslationResponse> get(
		TranslationSubjectKind kind,
		Long subjectId,
		TargetLanguage targetLanguage
	) {
		String key = key(kind, subjectId, targetLanguage);
		Object translatedText = redisTemplate.opsForHash().get(key, TRANSLATED_TEXT_FIELD);
		Object sourceLang = redisTemplate.opsForHash().get(key, SOURCE_LANG_FIELD);
		if (!(translatedText instanceof String translated) || !(sourceLang instanceof String source)) {
			return Optional.empty();
		}
		return Optional.of(new TranslationResponse(translated, source));
	}

	@Override
	public void put(
		TranslationSubjectKind kind,
		Long subjectId,
		TargetLanguage targetLanguage,
		TranslationResponse response
	) {
		String key = key(kind, subjectId, targetLanguage);
		redisTemplate.opsForHash().putAll(key, Map.of(
			TRANSLATED_TEXT_FIELD, response.translatedText(),
			SOURCE_LANG_FIELD, response.sourceLang()
		));
		redisTemplate.expire(key, CACHE_TTL);
	}

	private String key(TranslationSubjectKind kind, Long subjectId, TargetLanguage targetLanguage) {
		return "translation:cache:%s:%d:%s".formatted(kind.cacheKeyPart(), subjectId, targetLanguage.code());
	}
}
