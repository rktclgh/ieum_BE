package shinhan.fibri.ieum.ai.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbedding;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingGateway;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingUnavailableException;

class KnowledgeDocumentEmbedderTest {

	@Test
	void formatsAndEmbedsTheKnowledgeDocumentWithoutAQuestionPrefix() {
		CapturingGateway gateway = new CapturingGateway();
		KnowledgeDocumentEmbedder embedder = new KnowledgeDocumentEmbedder(
			new KnowledgeDocumentEmbeddingTextFormatter(),
			gateway
		);

		GeminiEmbedding embedding = embedder.embed("한국 버스 이용", "앞문으로 타고\n뒷문으로 내립니다.  ");

		assertThat(gateway.formattedText)
			.isEqualTo("title: 한국 버스 이용 | text: 앞문으로 타고\n뒷문으로 내립니다.  ")
			.doesNotContain("task: question answering", "query:");
		assertThat(gateway.calls).isOne();
		assertThat(embedding).isSameAs(gateway.embedding);
	}

	@Test
	void usesNoneForAnAbsentTitle() {
		CapturingGateway gateway = new CapturingGateway();
		KnowledgeDocumentEmbedder embedder = new KnowledgeDocumentEmbedder(
			new KnowledgeDocumentEmbeddingTextFormatter(),
			gateway
		);

		embedder.embed(null, "본문");

		assertThat(gateway.formattedText).isEqualTo("title: none | text: 본문");
	}

	@Test
	void rejectsBlankContentBeforeCallingTheProvider() {
		CapturingGateway gateway = new CapturingGateway();
		KnowledgeDocumentEmbedder embedder = new KnowledgeDocumentEmbedder(
			new KnowledgeDocumentEmbeddingTextFormatter(),
			gateway
		);

		assertThatThrownBy(() -> embedder.embed("제목", " \n "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("content must not be blank");
		assertThat(gateway.calls).isZero();
	}

	@Test
	void preservesTheSharedUnavailableFailureWithoutQuestionCoupling() {
		CapturingGateway gateway = new CapturingGateway();
		gateway.failure = new GeminiEmbeddingUnavailableException();
		KnowledgeDocumentEmbedder embedder = new KnowledgeDocumentEmbedder(
			new KnowledgeDocumentEmbeddingTextFormatter(),
			gateway
		);

		assertThatThrownBy(() -> embedder.embed("제목", "본문"))
			.isSameAs(gateway.failure)
			.isInstanceOf(GeminiEmbeddingUnavailableException.class);
	}

	private static List<Float> validEmbedding() {
		List<Float> values = new ArrayList<>();
		for (int index = 0; index < GeminiEmbedding.DIMENSIONS; index++) {
			values.add(index / 1000.0f);
		}
		return values;
	}

	private static final class CapturingGateway implements GeminiEmbeddingGateway {

		private final GeminiEmbedding embedding = new GeminiEmbedding(
			GeminiEmbedding.MODEL,
			validEmbedding()
		);
		private GeminiEmbeddingUnavailableException failure;
		private String formattedText;
		private int calls;

		@Override
		public GeminiEmbedding embed(String formattedText) {
			calls++;
			this.formattedText = formattedText;
			if (failure != null) {
				throw failure;
			}
			return embedding;
		}
	}
}
