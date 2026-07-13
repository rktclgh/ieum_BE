package shinhan.fibri.ieum.ai.question.finalization;

import java.util.Objects;

public record GroundedQuestionAnswerFinalization(
	QuestionTaskFence fence,
	QuestionAnswerMode answerMode,
	String content,
	QuestionAnswerFinalizationContext context
) {

	public GroundedQuestionAnswerFinalization {
		fence = Objects.requireNonNull(fence, "fence must not be null");
		answerMode = Objects.requireNonNull(answerMode, "answerMode must not be null");
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("content must not be blank");
		}
		context = Objects.requireNonNull(context, "context must not be null");
		if (context.evidence().isEmpty() || context.evidence().size() > 8) {
			throw new IllegalArgumentException("grounded evidence must contain 1 to 8 items");
		}
		if (context.generationProvider() == null
			|| context.generationModel() == null
			|| context.promptVersion() == null) {
			throw new IllegalArgumentException("grounded answers require generation provenance");
		}
		QuestionAnswerEvidenceValidator.validateForAnswer(
			answerMode,
			content,
			context.evidence()
		);
	}
}
