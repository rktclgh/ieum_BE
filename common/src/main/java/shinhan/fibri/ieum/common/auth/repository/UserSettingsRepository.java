package shinhan.fibri.ieum.common.auth.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.common.auth.domain.UserSettings;

public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {

	/**
	 * 수신자들의 언어 설정을 한 번에 읽는다.
	 *
	 * <p>웹푸시 문구는 브라우저/OS가 렌더하므로 서버가 수신자 언어를 알아야 한다. 알림센터·SSE는
	 * 프론트가 키로 렌더하니 이 조회가 필요 없고, 그래서 <b>푸시 경로에서만</b> 호출한다
	 * (notification/i18n/spec.md §7). 채팅 팬아웃은 수신자가 여러 명이라 IN 절로 한 번에 읽는다.
	 */
	@Query("SELECT s.userId AS userId, s.language AS language FROM UserSettings s WHERE s.userId IN :userIds")
	List<UserLanguageView> findLanguagesByUserIds(@Param("userIds") Collection<Long> userIds);

	interface UserLanguageView {
		Long getUserId();

		String getLanguage();
	}
}
