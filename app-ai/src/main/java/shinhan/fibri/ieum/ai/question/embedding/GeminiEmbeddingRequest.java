package shinhan.fibri.ieum.ai.question.embedding;

import java.util.Optional;

record GeminiEmbeddingRequest(String model, String text, int outputDimensionality) {

	GeminiEmbeddingRequest {
		if (model == null || model.isBlank()) {
			throw new IllegalArgumentException("model must not be blank");
		}
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("text must not be blank");
		}
		if (outputDimensionality != 768) {
			throw new IllegalArgumentException("outputDimensionality must be 768");
		}
		model = model.trim();
		text = text.trim();
	}

	Optional<String> taskType() {
		return Optional.empty();
	}
}
