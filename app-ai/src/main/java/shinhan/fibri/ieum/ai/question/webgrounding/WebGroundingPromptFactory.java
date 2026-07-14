package shinhan.fibri.ieum.ai.question.webgrounding;

import java.util.Objects;
import java.util.Optional;
import shinhan.fibri.ieum.ai.question.analysis.QuestionInputSnapshot;
import shinhan.fibri.ieum.ai.question.analysis.RegionContext;

public final class WebGroundingPromptFactory {

	private final WebQuestionPiiSanitizer sanitizer;

	public WebGroundingPromptFactory() {
		this(new WebQuestionPiiSanitizer());
	}

	public WebGroundingPromptFactory(WebQuestionPiiSanitizer sanitizer) {
		this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer must not be null");
	}

	public Optional<WebGroundingPrompt> create(
		QuestionInputSnapshot snapshot,
		RegionContext coarseRegion
	) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		Objects.requireNonNull(coarseRegion, "coarseRegion must not be null");

		String title = sanitizer.sanitize(snapshot.title(), snapshot.location());
		String content = sanitizer.sanitize(snapshot.content(), snapshot.location());
		boolean meaningfulTitle = sanitizer.hasMeaningfulText(title);
		boolean meaningfulContent = sanitizer.hasMeaningfulText(content);
		if (!meaningfulTitle && !meaningfulContent) {
			return Optional.empty();
		}
		title = meaningfulTitle ? title : WebQuestionPiiSanitizer.REDACTION_TOKEN;
		content = meaningfulContent ? content : WebQuestionPiiSanitizer.REDACTION_TOKEN;

		return Optional.of(new WebGroundingPrompt(title, content, coarseRegion(coarseRegion)));
	}

	private static WebGroundingRegion coarseRegion(RegionContext regionContext) {
		return new WebGroundingRegion(
			regionContext.country(),
			regionContext.sido(),
			regionContext.sigungu()
		);
	}
}
