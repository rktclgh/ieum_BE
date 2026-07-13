package shinhan.fibri.ieum.ai.question.checkpoint;

import java.util.Objects;

public enum QuestionTaskStage {
	ANALYZING("analyzing"),
	EMBEDDING("embedding"),
	RETRIEVING("retrieving"),
	GENERATING("generating"),
	VALIDATING("validating"),
	WEB_GROUNDING("web_grounding"),
	PERSISTING("persisting");

	private final String databaseValue;

	QuestionTaskStage(String databaseValue) {
		this.databaseValue = databaseValue;
	}

	public String databaseValue() {
		return databaseValue;
	}

	public boolean canAdvanceTo(QuestionTaskStage next) {
		Objects.requireNonNull(next, "next stage must not be null");
		return switch (this) {
			case RETRIEVING -> next == GENERATING || next == WEB_GROUNDING || next == PERSISTING;
			case GENERATING -> next == VALIDATING;
			case VALIDATING -> next == WEB_GROUNDING || next == PERSISTING;
			case WEB_GROUNDING -> next == PERSISTING;
			default -> false;
		};
	}
}
