package shinhan.fibri.ieum.ai.question.generation;

import java.util.Objects;

final class GeminiLocalAnswerClientException extends RuntimeException {

	private final LocalAnswerProviderFailureCode failureCode;

	GeminiLocalAnswerClientException(LocalAnswerProviderFailureCode failureCode) {
		this(failureCode, null);
	}

	GeminiLocalAnswerClientException(LocalAnswerProviderFailureCode failureCode, String ignoredRawDetail) {
		super("Gemini local answer client failed");
		this.failureCode = Objects.requireNonNull(failureCode, "failureCode must not be null");
	}

	LocalAnswerProviderFailureCode failureCode() {
		return failureCode;
	}
}
