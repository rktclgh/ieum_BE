package shinhan.fibri.ieum.main.admin.knowledge.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import shinhan.fibri.ieum.main.admin.knowledge.exception.InvalidKnowledgeCandidateCursorException;

final class KnowledgeCandidateCursor {

	private KnowledgeCandidateCursor() {
	}

	static String encode(Long candidateId) {
		return Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(String.valueOf(candidateId).getBytes(StandardCharsets.UTF_8));
	}

	static Long decode(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			long candidateId = Long.parseLong(decoded);
			if (candidateId < 1) {
				throw new InvalidKnowledgeCandidateCursorException();
			}
			return candidateId;
		} catch (IllegalArgumentException exception) {
			throw new InvalidKnowledgeCandidateCursorException();
		}
	}
}
