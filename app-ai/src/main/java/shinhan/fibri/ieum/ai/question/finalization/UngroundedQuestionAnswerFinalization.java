package shinhan.fibri.ieum.ai.question.finalization;

import java.util.Objects;

public record UngroundedQuestionAnswerFinalization(
	QuestionTaskFence fence,
	String content,
	QuestionAnswerFinalizationContext context
) {

	public UngroundedQuestionAnswerFinalization {
		fence = Objects.requireNonNull(fence, "fence must not be null");
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("content must not be blank");
		}
		context = Objects.requireNonNull(context, "context must not be null");
		if (!context.evidence().isEmpty()) {
			throw new IllegalArgumentException("ungrounded answers must not contain evidence");
		}
		if (context.generationProvider() == null
			|| context.generationModel() == null
			|| context.promptVersion() == null) {
			throw new IllegalArgumentException("ungrounded answers require generation provenance");
		}
	}
}
