package shinhan.fibri.ieum.ai.question.service;

public enum QuestionTaskFailure {
	STALE_FENCE(QuestionTaskFailureDisposition.DISCARD, null, null),
	PERMANENT_INPUT(
		QuestionTaskFailureDisposition.DEAD,
		"QUESTION_ANSWER_INVALID_INPUT",
		"Question answer input is invalid"
	),
	PERMANENT_CONFIGURATION(
		QuestionTaskFailureDisposition.DEAD,
		"QUESTION_ANSWER_INVALID_CONFIGURATION",
		"Question answer configuration is invalid"
	),
	EMBEDDING_UNAVAILABLE(
		QuestionTaskFailureDisposition.RETRY,
		"QUESTION_EMBEDDING_UNAVAILABLE",
		"Question embedding is temporarily unavailable"
	),
	PROVIDER_TIMEOUT(
		QuestionTaskFailureDisposition.RETRY,
		"QUESTION_ANSWER_PROVIDER_TIMEOUT",
		"Question answer provider timed out"
	),
	PROVIDER_RATE_LIMITED(
		QuestionTaskFailureDisposition.RETRY,
		"QUESTION_ANSWER_PROVIDER_RATE_LIMITED",
		"Question answer provider is rate limited"
	),
	WEB_GROUNDING_RATE_LIMITED(
		QuestionTaskFailureDisposition.RETRY,
		"QUESTION_ANSWER_WEB_GROUNDING_RATE_LIMITED",
		"Question web grounding provider is rate limited"
	),
	PROVIDER_UNAVAILABLE(
		QuestionTaskFailureDisposition.RETRY,
		"QUESTION_ANSWER_PROVIDER_UNAVAILABLE",
		"Question answer provider is temporarily unavailable"
	),
	GENERATION_INVALID_OUTPUT(
		QuestionTaskFailureDisposition.RETRY,
		"QUESTION_ANSWER_INVALID_MODEL_OUTPUT",
		"Question answer providers returned invalid output"
	),
	UNEXPECTED_TRANSIENT(
		QuestionTaskFailureDisposition.RETRY,
		"QUESTION_ANSWER_PROCESSING_FAILED",
		"Question answer processing failed"
	);

	private final QuestionTaskFailureDisposition disposition;
	private final String errorCode;
	private final String safeMessage;

	QuestionTaskFailure(
		QuestionTaskFailureDisposition disposition,
		String errorCode,
		String safeMessage
	) {
		this.disposition = disposition;
		this.errorCode = errorCode;
		this.safeMessage = safeMessage;
	}

	public QuestionTaskFailureDisposition disposition() {
		return disposition;
	}

	public String errorCode() {
		return errorCode;
	}

	public String safeMessage() {
		return safeMessage;
	}
}
