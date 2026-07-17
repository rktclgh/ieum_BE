package shinhan.fibri.ieum.main.admin.knowledge.dto;

import java.time.OffsetDateTime;

public record AdminKnowledgeCandidateSourceDetail(
	Long questionId,
	Long answerId,
	String displayName,
	String status,
	boolean active,
	OffsetDateTime validUntil,
	boolean eligible,
	String questionTitle,
	String questionContent,
	String answerContent,
	String chunkContent
) {
}
