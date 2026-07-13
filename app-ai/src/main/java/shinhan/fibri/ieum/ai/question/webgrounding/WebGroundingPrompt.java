package shinhan.fibri.ieum.ai.question.webgrounding;

import java.util.Objects;

public record WebGroundingPrompt(
	String title,
	String content,
	WebGroundingRegion coarseRegion
) {

	public WebGroundingPrompt {
		title = required(title, "title");
		content = required(content, "content");
		coarseRegion = Objects.requireNonNull(coarseRegion, "coarseRegion must not be null");
	}

	private static String required(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.trim();
	}
}
