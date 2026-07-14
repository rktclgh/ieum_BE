package shinhan.fibri.ieum.ai.question.embedding;

import java.util.Objects;
import shinhan.fibri.ieum.ai.question.analysis.QuestionInputSnapshot;

public final class QuestionEmbeddingTextFormatter {

	public String format(QuestionInputSnapshot snapshot) {
		QuestionInputSnapshot input = Objects.requireNonNull(snapshot, "snapshot must not be null");
		return "title: " + input.title() + " | text: " + input.content();
	}
}
