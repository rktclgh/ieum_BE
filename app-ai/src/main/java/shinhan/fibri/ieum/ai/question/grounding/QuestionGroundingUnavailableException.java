package shinhan.fibri.ieum.ai.question.grounding;

import java.util.Objects;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerProviderFailureCode;

public final class QuestionGroundingUnavailableException extends RuntimeException {

	private final LocalAnswerProviderFailureCode primaryFailure;
	private final LocalAnswerProviderFailureCode fallbackFailure;

	public QuestionGroundingUnavailableException(
		LocalAnswerProviderFailureCode primaryFailure,
		LocalAnswerProviderFailureCode fallbackFailure
	) {
		super("Local question grounding is unavailable");
		this.primaryFailure = Objects.requireNonNull(primaryFailure, "primaryFailure must not be null");
		this.fallbackFailure = Objects.requireNonNull(fallbackFailure, "fallbackFailure must not be null");
	}

	public LocalAnswerProviderFailureCode primaryFailure() {
		return primaryFailure;
	}

	public LocalAnswerProviderFailureCode fallbackFailure() {
		return fallbackFailure;
	}
}
