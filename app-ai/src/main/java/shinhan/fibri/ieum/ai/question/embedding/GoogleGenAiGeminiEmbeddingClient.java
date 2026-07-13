package shinhan.fibri.ieum.ai.question.embedding;

import com.google.genai.Client;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import java.util.List;
import java.util.Objects;

class GoogleGenAiGeminiEmbeddingClient implements GeminiEmbeddingClient {

	private final Client client;

	GoogleGenAiGeminiEmbeddingClient(Client client) {
		this.client = Objects.requireNonNull(client, "client must not be null");
	}

	@Override
	public List<Float> embed(GeminiEmbeddingRequest request) {
		EmbedContentResponse response = client.models.embedContent(
			request.model(),
			request.text(),
			EmbedContentConfig.builder()
				.outputDimensionality(request.outputDimensionality())
				.build()
		);
		return response.embeddings()
			.flatMap(embeddings -> embeddings.stream().findFirst())
			.flatMap(embedding -> embedding.values())
			.map(List::copyOf)
			.orElse(null);
	}
}
