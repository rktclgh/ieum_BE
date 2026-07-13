package shinhan.fibri.ieum.ai.question.embedding;

import java.util.List;
import java.util.Objects;
import shinhan.fibri.ieum.ai.question.service.EmbeddingUnavailableException;

public class GeminiQuestionEmbeddingGateway implements QuestionEmbeddingGateway {

	static final String MODEL = "gemini-embedding-2";
	static final int OUTPUT_DIMENSIONALITY = 768;
	private static final String QUESTION_ANSWERING_PREFIX = "task: question answering | query: ";
	private static final String UNAVAILABLE_MESSAGE = "Question embedding is unavailable";

	private final GeminiEmbeddingClient client;

	public GeminiQuestionEmbeddingGateway(GeminiEmbeddingClient client) {
		this.client = Objects.requireNonNull(client, "client must not be null");
	}

	@Override
	public QuestionEmbedding embed(String text) {
		GeminiEmbeddingRequest request = new GeminiEmbeddingRequest(
			MODEL,
			questionAnsweringQuery(text),
			OUTPUT_DIMENSIONALITY
		);
		List<Float> values;
		try {
			values = client.embed(request);
		} catch (RuntimeException exception) {
			throw unavailable();
		}
		if (!valid(values)) {
			throw unavailable();
		}
		return new QuestionEmbedding(MODEL, values);
	}

	private String questionAnsweringQuery(String text) {
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("text must not be blank");
		}
		return QUESTION_ANSWERING_PREFIX + text.trim();
	}

	private boolean valid(List<Float> values) {
		if (values == null || values.size() != OUTPUT_DIMENSIONALITY) {
			return false;
		}
		for (Float value : values) {
			if (value == null || !Float.isFinite(value)) {
				return false;
			}
		}
		return true;
	}

	private EmbeddingUnavailableException unavailable() {
		return new EmbeddingUnavailableException(UNAVAILABLE_MESSAGE);
	}
}
