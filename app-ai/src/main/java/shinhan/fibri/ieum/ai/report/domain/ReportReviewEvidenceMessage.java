package shinhan.fibri.ieum.ai.report.domain;

import java.util.regex.Pattern;

public record ReportReviewEvidenceMessage(
	long messageId,
	String actor,
	String content,
	boolean verifiedImage
) {

	private static final Pattern ACTOR_ALIAS = Pattern.compile("reported_user|reporter|other_actor_[1-9][0-9]*");

	public ReportReviewEvidenceMessage {
		if (messageId < 1) {
			throw new IllegalArgumentException("messageId must be positive");
		}
		if (actor == null || !ACTOR_ALIAS.matcher(actor).matches()) {
			throw new IllegalArgumentException("actor must be a supported alias");
		}
	}

	public boolean hasText() {
		return content != null && !content.isBlank();
	}
}
