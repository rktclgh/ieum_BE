package shinhan.fibri.ieum.ai.question.embedding;

import java.util.List;
import java.util.Objects;

public record QuestionEmbedding(String model, List<Float> values) {

	public QuestionEmbedding {
		if (model == null || model.isBlank()) {
			throw new IllegalArgumentException("model must not be blank");
		}
		Objects.requireNonNull(values, "values must not be null");
		model = model.trim();
		values = List.copyOf(values);
	}
}
