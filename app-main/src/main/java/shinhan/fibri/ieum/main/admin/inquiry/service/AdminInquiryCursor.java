package shinhan.fibri.ieum.main.admin.inquiry.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import shinhan.fibri.ieum.main.admin.user.exception.InvalidAdminCursorException;

final class AdminInquiryCursor {

	private AdminInquiryCursor() {
	}

	static String encode(Long inquiryId) {
		if (inquiryId == null) {
			return null;
		}
		return Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(String.valueOf(inquiryId).getBytes(StandardCharsets.UTF_8));
	}

	static Long decode(String cursor) {
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
