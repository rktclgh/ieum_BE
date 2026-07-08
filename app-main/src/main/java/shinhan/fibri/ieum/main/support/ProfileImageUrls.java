package shinhan.fibri.ieum.main.support;

import shinhan.fibri.ieum.common.auth.domain.User;

public final class ProfileImageUrls {

	private ProfileImageUrls() {
	}

	public static String of(User user) {
		if (user.getProfileFileId() == null) {
			return null;
		}
		return "/api/v1/files/" + user.getProfileFileId();
	}
}
