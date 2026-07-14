package shinhan.fibri.ieum.ai.embedding;

public final class GeminiEmbeddingUnavailableException extends RuntimeException {

	public GeminiEmbeddingUnavailableException() {
		super("Gemini embedding is unavailable");
	}
}
