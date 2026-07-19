package shinhan.fibri.ieum.main.support;

import java.util.UUID;
import shinhan.fibri.ieum.common.auth.domain.User;

public final class ProfileImageUrls {

	private ProfileImageUrls() {
	}

	public static String of(User user) {
		return of(user.getProfileFileId());
	}

	/**
	 * 엔티티를 로드하지 않는 프로젝션 조회용. 파일 id만 들고 있을 때 사용한다.
	 */
	public static String of(UUID profileFileId) {
		if (profileFileId == null) {
			return null;
		}
		return "/api/v1/files/" + profileFileId;
	}
}
