package shinhan.fibri.ieum.ai.question.webgrounding;

import java.util.Objects;

public final class QuestionWebGroundingUnavailableException extends RuntimeException {

	private final WebGroundingFailureCode failureCode;

	public QuestionWebGroundingUnavailableException(WebGroundingFailureCode failureCode) {
		super("Question web grounding is unavailable");
		this.failureCode = Objects.requireNonNull(failureCode, "failureCode must not be null");
	}

	public WebGroundingFailureCode failureCode() {
		return failureCode;
	}
}
