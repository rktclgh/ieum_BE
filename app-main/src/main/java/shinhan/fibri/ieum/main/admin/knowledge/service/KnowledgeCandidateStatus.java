package shinhan.fibri.ieum.main.admin.knowledge.service;

import java.util.Set;
import shinhan.fibri.ieum.main.admin.knowledge.exception.InvalidKnowledgeCandidateStatusException;

final class KnowledgeCandidateStatus {

	private static final Set<String> ALLOWED = Set.of("pending", "approved", "rejected", "invalidated");

	private KnowledgeCandidateStatus() {
	}

	static String normalize(String status) {
		if (status == null || status.isBlank()) {
			return null;
		}
		String normalized = status.trim().toLowerCase();
		if (!ALLOWED.contains(normalized)) {
			throw new InvalidKnowledgeCandidateStatusException();
		}
		return normalized;
	}
}
