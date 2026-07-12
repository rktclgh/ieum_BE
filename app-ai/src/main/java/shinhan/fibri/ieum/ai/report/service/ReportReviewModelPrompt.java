package shinhan.fibri.ieum.ai.report.service;

import java.util.List;

public record ReportReviewModelPrompt(
	String systemInstruction,
	String userInstruction,
	List<ReportReviewModelPromptImage> images
) {

	public ReportReviewModelPrompt {
		if (systemInstruction == null || systemInstruction.isBlank() || userInstruction == null || userInstruction.isBlank() || images == null) {
			throw new IllegalArgumentException("report review model prompt must be valid");
		}
		images = List.copyOf(images);
	}
}
