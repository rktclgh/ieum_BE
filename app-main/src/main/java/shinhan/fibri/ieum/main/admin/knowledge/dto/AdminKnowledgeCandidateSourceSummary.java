package shinhan.fibri.ieum.main.admin.knowledge.dto;

import java.time.OffsetDateTime;

public record AdminKnowledgeCandidateSourceSummary(
	Long questionId,
	Long answerId,
	String displayName,
	String status,
	boolean active,
	OffsetDateTime validUntil,
	boolean eligible
) {
}
