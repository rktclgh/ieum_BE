package shinhan.fibri.ieum.ai.question.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class QuestionTaskFailureTest {

	@Test
	void exposesOnlyTheFiniteSafePersistenceContract() {
		assertThat(QuestionTaskFailure.values())
			.extracting(
				QuestionTaskFailure::disposition,
				QuestionTaskFailure::errorCode,
				QuestionTaskFailure::safeMessage
			)
			.containsExactly(
				row(QuestionTaskFailureDisposition.DISCARD, null, null),
				row(QuestionTaskFailureDisposition.DEAD,
					"QUESTION_ANSWER_INVALID_INPUT", "Question answer input is invalid"),
				row(QuestionTaskFailureDisposition.DEAD,
					"QUESTION_ANSWER_INVALID_CONFIGURATION", "Question answer configuration is invalid"),
				row(QuestionTaskFailureDisposition.RETRY,
					"QUESTION_EMBEDDING_UNAVAILABLE", "Question embedding is temporarily unavailable"),
				row(QuestionTaskFailureDisposition.RETRY,
					"QUESTION_ANSWER_PROVIDER_TIMEOUT", "Question answer provider timed out"),
				row(QuestionTaskFailureDisposition.RETRY,
					"QUESTION_ANSWER_PROVIDER_RATE_LIMITED", "Question answer provider is rate limited"),
				row(QuestionTaskFailureDisposition.RETRY,
					"QUESTION_ANSWER_WEB_GROUNDING_RATE_LIMITED", "Question web grounding provider is rate limited"),
				row(QuestionTaskFailureDisposition.RETRY,
					"QUESTION_ANSWER_PROVIDER_UNAVAILABLE", "Question answer provider is temporarily unavailable"),
				row(QuestionTaskFailureDisposition.RETRY,
					"QUESTION_ANSWER_INVALID_MODEL_OUTPUT", "Question answer providers returned invalid output"),
				row(QuestionTaskFailureDisposition.RETRY,
					"QUESTION_ANSWER_PROCESSING_FAILED", "Question answer processing failed")
			);
	}

	@Test
	void explicitFailureExceptionRejectsTheDiscardOnlyFailure() {
		assertThatThrownBy(() -> new QuestionTaskFailureException(QuestionTaskFailure.STALE_FENCE))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("discard failures cannot be thrown explicitly");
	}

	@Test
	void explicitFailureExceptionNeverExposesItsCauseMessage() {
		QuestionTaskFailureException exception = new QuestionTaskFailureException(
			QuestionTaskFailure.PROVIDER_UNAVAILABLE,
			new RuntimeException("question body, address and provider payload")
		);

		assertThat(exception.failure()).isEqualTo(QuestionTaskFailure.PROVIDER_UNAVAILABLE);
		assertThat(exception.getMessage()).isEqualTo("Question answer provider is temporarily unavailable");
		assertThat(exception.getMessage()).doesNotContain("question body", "address", "provider payload");
	}

	private static org.assertj.core.groups.Tuple row(
		QuestionTaskFailureDisposition disposition,
		String errorCode,
		String message
	) {
		return org.assertj.core.groups.Tuple.tuple(disposition, errorCode, message);
	}
}
