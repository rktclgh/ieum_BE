package shinhan.fibri.ieum.main.pin.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import shinhan.fibri.ieum.main.pin.exception.InvalidPinRequestException;

public final class PinCursor {

	private PinCursor() {
	}

	public static String encode(Long pinId) {
		if (pinId == null) {
			return null;
		}
		return Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(String.valueOf(pinId).getBytes(StandardCharsets.UTF_8));
	}

	public static Long decode(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			return Long.valueOf(decoded);
		} catch (IllegalArgumentException exception) {
			throw new InvalidPinRequestException("INVALID_CURSOR", "cursor", "Invalid cursor");
		}
	}
}
