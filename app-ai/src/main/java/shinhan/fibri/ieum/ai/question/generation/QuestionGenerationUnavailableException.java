package shinhan.fibri.ieum.ai.question.generation;

import java.util.Objects;

public final class QuestionGenerationUnavailableException extends RuntimeException {

	private final LocalAnswerProviderFailureCode primaryFailure;
	private final LocalAnswerProviderFailureCode fallbackFailure;

	public QuestionGenerationUnavailableException(
		LocalAnswerProviderFailureCode primaryFailure,
		LocalAnswerProviderFailureCode fallbackFailure
	) {
		super("Local question answer generation is unavailable");
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
