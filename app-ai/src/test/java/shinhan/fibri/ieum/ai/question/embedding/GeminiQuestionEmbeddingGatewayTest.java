package shinhan.fibri.ieum.ai.question.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbedding;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingGateway;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingUnavailableException;
import shinhan.fibri.ieum.ai.question.service.EmbeddingUnavailableException;

class GeminiQuestionEmbeddingGatewayTest {

	@Test
	void addsTheQuestionAnsweringPrefixOnceAndDelegatesToTheSharedCore() {
		FakeGeminiEmbeddingGateway sharedGateway = new FakeGeminiEmbeddingGateway();
		QuestionEmbeddingGateway gateway = new GeminiQuestionEmbeddingGateway(sharedGateway);

		QuestionEmbedding embedding = gateway.embed("  퇴직연금 수령 조건은?  ");

		assertThat(sharedGateway.formattedText)
			.isEqualTo("task: question answering | query: 퇴직연금 수령 조건은?");
		assertThat(embedding.model()).isEqualTo("gemini-embedding-2");
		assertThat(embedding.values()).hasSize(768);
	}

	@Test
	void mapsTheSharedUnavailableFailureToTheExistingQuestionFailure() {
		FakeGeminiEmbeddingGateway sharedGateway = new FakeGeminiEmbeddingGateway();
		sharedGateway.failure = new GeminiEmbeddingUnavailableException();
		QuestionEmbeddingGateway gateway = new GeminiQuestionEmbeddingGateway(sharedGateway);

		assertThatThrownBy(() -> gateway.embed("provider raw content"))
			.isInstanceOf(EmbeddingUnavailableException.class)
			.hasMessage("Question embedding is unavailable")
			.hasMessageNotContaining("provider raw content")
			.hasMessageNotContaining("Gemini embedding");
	}

	@Test
	void rejectsBlankQuestionInputBeforeCallingTheSharedCore() {
		FakeGeminiEmbeddingGateway sharedGateway = new FakeGeminiEmbeddingGateway();
		QuestionEmbeddingGateway gateway = new GeminiQuestionEmbeddingGateway(sharedGateway);

		assertThatThrownBy(() -> gateway.embed(" \n "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("text must not be blank");
		assertThat(sharedGateway.calls).isZero();
	}

	private static List<Float> validEmbedding() {
		List<Float> values = new ArrayList<>();
		for (int index = 0; index < 768; index++) {
			values.add(index / 1000.0f);
		}
		return values;
	}

	private static final class FakeGeminiEmbeddingGateway implements GeminiEmbeddingGateway {

		private RuntimeException failure;
		private String formattedText;
		private int calls;

		@Override
		public GeminiEmbedding embed(String formattedText) {
			calls++;
			this.formattedText = formattedText;
			if (failure != null) {
				throw failure;
			}
			return new GeminiEmbedding(GeminiEmbedding.MODEL, validEmbedding());
		}
	}
}
