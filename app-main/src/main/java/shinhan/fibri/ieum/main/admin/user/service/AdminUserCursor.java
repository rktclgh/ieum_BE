package shinhan.fibri.ieum.main.admin.user.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import shinhan.fibri.ieum.main.admin.user.exception.InvalidAdminCursorException;

public final class AdminUserCursor {

	private AdminUserCursor() {
	}

	public static String encode(Long userId) {
		if (userId == null) {
			return null;
		}
		return Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(String.valueOf(userId).getBytes(StandardCharsets.UTF_8));
	}

	public static Long decode(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			return Long.valueOf(decoded);
		} catch (IllegalArgumentException exception) {
			throw new InvalidAdminCursorException();
		}
	}
}
