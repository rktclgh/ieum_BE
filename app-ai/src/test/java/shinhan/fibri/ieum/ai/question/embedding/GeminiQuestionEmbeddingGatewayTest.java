package shinhan.fibri.ieum.ai.question.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.question.service.EmbeddingUnavailableException;

class GeminiQuestionEmbeddingGatewayTest {

	@Test
	void embedsQuestionAnsweringQueryWithGeminiEmbedding2And768DimensionsWithoutTaskType() {
		FakeGeminiEmbeddingClient client = new FakeGeminiEmbeddingClient(validEmbedding());
		QuestionEmbeddingGateway gateway = new GeminiQuestionEmbeddingGateway(client);

		QuestionEmbedding embedding = gateway.embed("퇴직연금 수령 조건은?");

		assertThat(client.requestedModel).isEqualTo("gemini-embedding-2");
		assertThat(client.requestedText)
			.isEqualTo("task: question answering | query: 퇴직연금 수령 조건은?");
		assertThat(client.requestedOutputDimensionality).isEqualTo(768);
		assertThat(client.taskTypeSet).isFalse();
		assertThat(embedding.model()).isEqualTo("gemini-embedding-2");
		assertThat(embedding.values()).hasSize(768);
	}

	@Test
	void rejectsAbsentEmbeddingWithoutSurfacingProviderContent() {
		FakeGeminiEmbeddingClient client = new FakeGeminiEmbeddingClient(null);
		QuestionEmbeddingGateway gateway = new GeminiQuestionEmbeddingGateway(client);

		assertThatThrownBy(() -> gateway.embed("provider raw content"))
			.isInstanceOf(EmbeddingUnavailableException.class)
			.hasMessage("Question embedding is unavailable")
			.hasMessageNotContaining("provider raw content");
	}

	@Test
	void rejectsProviderExceptionWithoutSurfacingProviderMessage() {
		FakeGeminiEmbeddingClient client = new FakeGeminiEmbeddingClient(validEmbedding());
		client.failure = new RuntimeException("raw provider message");
		QuestionEmbeddingGateway gateway = new GeminiQuestionEmbeddingGateway(client);

		assertThatThrownBy(() -> gateway.embed("퇴직연금"))
			.isInstanceOf(EmbeddingUnavailableException.class)
			.hasMessage("Question embedding is unavailable")
			.hasMessageNotContaining("raw provider message");
	}

	@Test
	void rejectsWrongLengthEmbedding() {
		FakeGeminiEmbeddingClient client = new FakeGeminiEmbeddingClient(List.of(0.1f));
		QuestionEmbeddingGateway gateway = new GeminiQuestionEmbeddingGateway(client);

		assertThatThrownBy(() -> gateway.embed("퇴직연금"))
			.isInstanceOf(EmbeddingUnavailableException.class)
			.hasMessage("Question embedding is unavailable");
	}

	@Test
	void rejectsNonFiniteEmbeddingValue() {
		List<Float> values = validEmbedding();
		values.set(10, Float.NaN);
		FakeGeminiEmbeddingClient client = new FakeGeminiEmbeddingClient(values);
		QuestionEmbeddingGateway gateway = new GeminiQuestionEmbeddingGateway(client);

		assertThatThrownBy(() -> gateway.embed("퇴직연금"))
			.isInstanceOf(EmbeddingUnavailableException.class)
			.hasMessage("Question embedding is unavailable");
	}

	private static List<Float> validEmbedding() {
		List<Float> values = new ArrayList<>();
		for (int index = 0; index < 768; index++) {
			values.add(index / 1000.0f);
		}
		return values;
	}

	private static final class FakeGeminiEmbeddingClient implements GeminiEmbeddingClient {

		private final List<Float> values;
		private RuntimeException failure;
		private String requestedModel;
		private String requestedText;
		private Integer requestedOutputDimensionality;
		private boolean taskTypeSet;

		private FakeGeminiEmbeddingClient(List<Float> values) {
			this.values = values;
		}

		@Override
		public List<Float> embed(GeminiEmbeddingRequest request) {
			if (failure != null) {
				throw failure;
			}
			requestedModel = request.model();
			requestedText = request.text();
			requestedOutputDimensionality = request.outputDimensionality();
			taskTypeSet = request.taskType().isPresent();
			return values;
		}
	}
}
