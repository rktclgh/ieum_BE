package shinhan.fibri.ieum.ai.question.grounding;

import java.util.Objects;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerProviderFailureCode;

final class GroundingProviderException extends RuntimeException {

	private final LocalAnswerProviderFailureCode failureCode;

	GroundingProviderException(LocalAnswerProviderFailureCode failureCode) {
		super("Local grounding provider failed");
		this.failureCode = Objects.requireNonNull(failureCode, "failureCode must not be null");
	}

	LocalAnswerProviderFailureCode failureCode() {
		return failureCode;
	}
}
