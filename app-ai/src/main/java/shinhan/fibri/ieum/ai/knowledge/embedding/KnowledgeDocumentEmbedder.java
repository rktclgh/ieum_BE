package shinhan.fibri.ieum.ai.knowledge.embedding;

import java.util.Objects;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbedding;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingGateway;

public final class KnowledgeDocumentEmbedder {

	private final KnowledgeDocumentEmbeddingTextFormatter formatter;
	private final GeminiEmbeddingGateway embeddingGateway;

	public KnowledgeDocumentEmbedder(
		KnowledgeDocumentEmbeddingTextFormatter formatter,
		GeminiEmbeddingGateway embeddingGateway
	) {
		this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
		this.embeddingGateway = Objects.requireNonNull(embeddingGateway, "embeddingGateway must not be null");
	}

	public GeminiEmbedding embed(String title, String content) {
		return embeddingGateway.embed(formatter.format(title, content));
	}
}
