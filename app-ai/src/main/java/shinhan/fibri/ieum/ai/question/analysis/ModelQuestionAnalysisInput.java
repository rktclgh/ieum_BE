package shinhan.fibri.ieum.ai.question.analysis;

import java.util.Objects;

public record ModelQuestionAnalysisInput(
	String title,
	String content,
	RegionContext coarseRegion
) {

	public ModelQuestionAnalysisInput {
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("title must not be blank");
		}
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("content must not be blank");
		}
		Objects.requireNonNull(coarseRegion, "coarseRegion must not be null");
	}
}
