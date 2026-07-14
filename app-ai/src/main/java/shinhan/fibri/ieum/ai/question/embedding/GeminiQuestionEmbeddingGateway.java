package shinhan.fibri.ieum.ai.question.embedding;

import java.util.Objects;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbedding;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingGateway;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingUnavailableException;
import shinhan.fibri.ieum.ai.question.service.EmbeddingUnavailableException;

public class GeminiQuestionEmbeddingGateway implements QuestionEmbeddingGateway {

	private static final String QUESTION_ANSWERING_PREFIX = "task: question answering | query: ";
	private static final String UNAVAILABLE_MESSAGE = "Question embedding is unavailable";

	private final GeminiEmbeddingGateway embeddingGateway;

	public GeminiQuestionEmbeddingGateway(GeminiEmbeddingGateway embeddingGateway) {
		this.embeddingGateway = Objects.requireNonNull(embeddingGateway, "embeddingGateway must not be null");
	}

	@Override
	public QuestionEmbedding embed(String text) {
		GeminiEmbedding embedding;
		try {
			embedding = embeddingGateway.embed(questionAnsweringQuery(text));
		} catch (GeminiEmbeddingUnavailableException exception) {
			throw unavailable();
		}
		return new QuestionEmbedding(embedding.model(), embedding.values());
	}

	private String questionAnsweringQuery(String text) {
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("text must not be blank");
		}
		return QUESTION_ANSWERING_PREFIX + text.trim();
	}

	private EmbeddingUnavailableException unavailable() {
		return new EmbeddingUnavailableException(UNAVAILABLE_MESSAGE);
	}
}
