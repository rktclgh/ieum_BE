package shinhan.fibri.ieum.main.notification.message;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.auth.repository.UserSettingsRepository;

/**
 * 웹푸시 페이로드에 실을 <b>수신자</b> 언어를 해석한다.
 *
 * <p>디바이스 로케일이나 요청자의 Accept-Language가 아니라 {@code user_settings.language}가 권위다
 * — 알림은 발송자와 수신자가 다르기 때문이다(notification/i18n/spec.md §2.1).
 *
 * <p>이 조회는 <b>푸시 경로에서만</b>, 그리고 항상 비즈니스 트랜잭션 커밋 이후에 일어난다.
 * 알림센터·SSE는 프론트가 키로 렌더하므로 언어를 알 필요가 없다.
 */
@Component
public class NotificationLanguageResolver {

	/** 설정이 없거나 조회에 실패했을 때. DB 기본값과 같다. */
	public static final String DEFAULT_LANGUAGE = "ko";

	private static final Logger log = LoggerFactory.getLogger(NotificationLanguageResolver.class);

	private final UserSettingsRepository userSettingsRepository;

	public NotificationLanguageResolver(UserSettingsRepository userSettingsRepository) {
		this.userSettingsRepository = userSettingsRepository;
	}

	public String resolve(Long userId) {
		return resolveAll(java.util.List.of(userId)).getOrDefault(userId, DEFAULT_LANGUAGE);
	}

	/**
	 * 채팅 팬아웃처럼 수신자가 여럿일 때 한 번에 읽는다.
	 *
	 * <p>조회가 실패해도 예외를 던지지 않는다 — 언어를 못 읽었다고 푸시 자체를 포기하는 것보다
	 * 기본 언어로라도 보내는 편이 낫다. 설정 행이 없는 사용자도 기본값으로 채운다.
	 */
	public Map<Long, String> resolveAll(Collection<Long> userIds) {
		Map<Long, String> resolved = new HashMap<>();
		if (userIds.isEmpty()) {
			return resolved;
		}
		try {
			for (UserSettingsRepository.UserLanguageView view : userSettingsRepository.findLanguagesByUserIds(userIds)) {
				String language = view.getLanguage();
				resolved.put(view.getUserId(), language == null || language.isBlank() ? DEFAULT_LANGUAGE : language);
			}
		}
		catch (RuntimeException exception) {
			log.warn(
				"event=notification_language_lookup_failed userCount={} failureType={}",
				userIds.size(),
				exception.getClass().getSimpleName()
			);
		}
		for (Long userId : userIds) {
			resolved.putIfAbsent(userId, DEFAULT_LANGUAGE);
		}
		return resolved;
	}
}
