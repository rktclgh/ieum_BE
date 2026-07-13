package shinhan.fibri.ieum.ai.question.embedding;

import java.util.List;

interface GeminiEmbeddingClient {

	List<Float> embed(GeminiEmbeddingRequest request);
}
