package shinhan.fibri.ieum.ai.embedding;

import com.google.genai.Client;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import java.util.List;
import java.util.Objects;

public final class GoogleGenAiGeminiEmbeddingGateway implements GeminiEmbeddingGateway {

	private final Transport transport;

	public GoogleGenAiGeminiEmbeddingGateway(Client client) {
		this(googleTransport(client));
	}

	GoogleGenAiGeminiEmbeddingGateway(Transport transport) {
		this.transport = Objects.requireNonNull(transport, "transport must not be null");
	}

	@Override
	public GeminiEmbedding embed(String formattedText) {
		if (formattedText == null || formattedText.isBlank()) {
			throw new IllegalArgumentException("formattedText must not be blank");
		}
		try {
			List<Float> values = transport.embed(
				GeminiEmbedding.MODEL,
				formattedText,
				embeddingConfig()
			);
			return new GeminiEmbedding(GeminiEmbedding.MODEL, values);
		}
		catch (RuntimeException exception) {
			throw new GeminiEmbeddingUnavailableException();
		}
	}

	static EmbedContentConfig embeddingConfig() {
		return EmbedContentConfig.builder()
			.outputDimensionality(GeminiEmbedding.DIMENSIONS)
			.build();
	}

	private static Transport googleTransport(Client client) {
		Client requiredClient = Objects.requireNonNull(client, "client must not be null");
		return (model, text, config) -> values(
			requiredClient.models.embedContent(model, text, config)
		);
	}

	private static List<Float> values(EmbedContentResponse response) {
		if (response == null) {
			return null;
		}
		return response.embeddings()
			.flatMap(embeddings -> embeddings.stream().findFirst())
			.flatMap(embedding -> embedding.values())
			.map(List::copyOf)
			.orElse(null);
	}

	@FunctionalInterface
	interface Transport {

		List<Float> embed(String model, String text, EmbedContentConfig config);
	}
}
