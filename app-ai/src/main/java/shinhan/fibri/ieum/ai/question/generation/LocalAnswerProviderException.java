package shinhan.fibri.ieum.ai.question.generation;

import java.util.Objects;

class LocalAnswerProviderException extends RuntimeException {

	private final LocalAnswerProviderFailureCode failureCode;

	LocalAnswerProviderException(LocalAnswerProviderFailureCode failureCode) {
		super("Local answer provider failed");
		this.failureCode = Objects.requireNonNull(failureCode, "failureCode must not be null");
	}

	LocalAnswerProviderFailureCode failureCode() {
		return failureCode;
	}
}
