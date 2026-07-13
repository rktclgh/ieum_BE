package shinhan.fibri.ieum.ai.question.generation;

public final class InvalidLocalAnswerOutputException extends LocalAnswerProviderException {

	InvalidLocalAnswerOutputException(LocalAnswerProviderFailureCode failureCode) {
		super(failureCode);
		if (failureCode != LocalAnswerProviderFailureCode.empty_response
			&& failureCode != LocalAnswerProviderFailureCode.invalid_output) {
			throw new IllegalArgumentException("Invalid output failure code is not allowed");
		}
	}

	public LocalAnswerProviderFailureCode failureCode() {
		return super.failureCode();
	}
}
