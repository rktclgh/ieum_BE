package shinhan.fibri.ieum.ai.question.service;

import java.util.Objects;

public final class QuestionTaskFailureException extends RuntimeException {

	private final QuestionTaskFailure failure;

	public QuestionTaskFailureException(QuestionTaskFailure failure) {
		this(failure, null);
	}

	public QuestionTaskFailureException(QuestionTaskFailure failure, Throwable cause) {
		super(safeMessage(failure), cause);
		this.failure = failure;
	}

	public QuestionTaskFailure failure() {
		return failure;
	}

	private static String safeMessage(QuestionTaskFailure failure) {
		QuestionTaskFailure required = Objects.requireNonNull(failure, "failure must not be null");
		if (required.disposition() == QuestionTaskFailureDisposition.DISCARD) {
			throw new IllegalArgumentException("discard failures cannot be thrown explicitly");
		}
		return required.safeMessage();
	}
}
