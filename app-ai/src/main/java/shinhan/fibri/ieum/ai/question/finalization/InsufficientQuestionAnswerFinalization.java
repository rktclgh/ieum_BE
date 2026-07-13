package shinhan.fibri.ieum.ai.question.finalization;

import java.util.Objects;

public record InsufficientQuestionAnswerFinalization(
	QuestionTaskFence fence,
	QuestionAnswerFinalizationContext context
) {

	public InsufficientQuestionAnswerFinalization {
		fence = Objects.requireNonNull(fence, "fence must not be null");
		context = Objects.requireNonNull(context, "context must not be null");
		if (!context.evidence().isEmpty()) {
			throw new IllegalArgumentException("insufficient evidence snapshot must be empty");
		}
	}
}
