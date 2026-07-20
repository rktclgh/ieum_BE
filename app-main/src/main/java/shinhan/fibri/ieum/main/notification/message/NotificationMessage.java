package shinhan.fibri.ieum.main.notification.message;

import java.util.Map;
import java.util.Objects;

/**
 * 알림 문구를 문장이 아니라 <b>키 + 파라미터</b>로 표현한다.
 *
 * <p>번역은 서버가 하지 않는다. 서버는 어떤 사건이 일어났고 그 사건의 값이 무엇인지만 발행하고,
 * 각 언어로 뭐라고 말할지는 프론트 카탈로그({@code ieum_fe/src/lib/i18n/messages/*})가 정한다.
 *
 * <p>{@code params}는 <b>스냅샷 값</b>이다. userId 같은 참조를 넣으면 상대가 닉네임을 바꿨을 때
 * 이미 전달된 과거 알림의 내용이 소급 변경되므로, 발송 시점의 값을 굳혀서 담는다.
 */
public record NotificationMessage(String key, Map<String, String> params) {

	public NotificationMessage {
		Objects.requireNonNull(key, "key must not be null");
		params = params == null ? Map.of() : Map.copyOf(params);
	}

	public static NotificationMessage of(String key) {
		return new NotificationMessage(key, Map.of());
	}

	public static NotificationMessage of(String key, Map<String, String> params) {
		return new NotificationMessage(key, params);
	}
}
