package shinhan.fibri.ieum.ai.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.genai.types.EmbedContentConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoogleGenAiGeminiEmbeddingGatewayTest {

	@Test
	void embedsTheExactFormattedTextWithThePinnedModelAndRequestShape() {
		CapturingTransport transport = new CapturingTransport(validEmbedding());
		GeminiEmbeddingGateway gateway = new GoogleGenAiGeminiEmbeddingGateway(transport);
		String formattedText = "  title: 체류 안내 | text: 첫 줄\n둘째 줄  ";

		GeminiEmbedding embedding = gateway.embed(formattedText);

		assertThat(transport.model).isEqualTo("gemini-embedding-2");
		assertThat(transport.text).isEqualTo(formattedText);
		assertThat(transport.config.outputDimensionality()).contains(768);
		assertThat(transport.config.taskType()).isEmpty();
		assertThat(transport.config.title()).isEmpty();
		assertThat(embedding.model()).isEqualTo("gemini-embedding-2");
		assertThat(embedding.values()).hasSize(768);
	}

	@Test
	void returnsAnImmutableDefensiveVectorCopy() {
		List<Float> providerValues = validEmbedding();
		GeminiEmbedding embedding = new GoogleGenAiGeminiEmbeddingGateway(
			new CapturingTransport(providerValues)
		).embed("title: 안내 | text: 내용");

		providerValues.set(0, 999f);

		assertThat(embedding.values().getFirst()).isZero();
		assertThatThrownBy(() -> embedding.values().add(1f))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void rejectsMalformedProviderVectorsWithoutLeakingInput() {
		List<Float> withNull = validEmbedding();
		withNull.set(10, null);
		List<Float> withNan = validEmbedding();
		withNan.set(10, Float.NaN);
		List<Float> withInfinity = validEmbedding();
		withInfinity.set(10, Float.POSITIVE_INFINITY);
		List<Float> tooLong = validEmbedding();
		tooLong.add(0.1f);

		for (List<Float> invalid : java.util.Arrays.asList(
			null,
			validEmbedding().subList(0, 767),
			tooLong,
			withNull,
			withNan,
			withInfinity
		)) {
			GeminiEmbeddingGateway gateway = new GoogleGenAiGeminiEmbeddingGateway(
				new CapturingTransport(invalid)
			);

			assertThatThrownBy(() -> gateway.embed("provider input must stay private"))
				.isInstanceOf(GeminiEmbeddingUnavailableException.class)
				.hasMessage("Gemini embedding is unavailable")
				.hasMessageNotContaining("provider input");
		}
	}

	@Test
	void sanitizesProviderFailures() {
		GoogleGenAiGeminiEmbeddingGateway.Transport failing = (model, text, config) -> {
			throw new IllegalStateException("raw provider response");
		};
		GeminiEmbeddingGateway gateway = new GoogleGenAiGeminiEmbeddingGateway(failing);

		assertThatThrownBy(() -> gateway.embed("title: 안내 | text: 내용"))
			.isInstanceOf(GeminiEmbeddingUnavailableException.class)
			.hasMessage("Gemini embedding is unavailable")
			.hasMessageNotContaining("raw provider response");
	}

	@Test
	void rejectsBlankCallerInputBeforeCallingTheProvider() {
		CapturingTransport transport = new CapturingTransport(validEmbedding());
		GeminiEmbeddingGateway gateway = new GoogleGenAiGeminiEmbeddingGateway(transport);

		assertThatThrownBy(() -> gateway.embed(" \n "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("formattedText must not be blank");
		assertThat(transport.calls).isZero();
	}

	@Test
	void rejectsAResultForAnyOtherEmbeddingModel() {
		assertThatThrownBy(() -> new GeminiEmbedding("gemini-embedding-001", validEmbedding()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("model must be gemini-embedding-2");
	}

	private static List<Float> validEmbedding() {
		List<Float> values = new ArrayList<>();
		for (int index = 0; index < 768; index++) {
			values.add(index / 1000.0f);
		}
		return values;
	}

	private static final class CapturingTransport implements GoogleGenAiGeminiEmbeddingGateway.Transport {

		private final List<Float> values;
		private String model;
		private String text;
		private EmbedContentConfig config;
		private int calls;

		private CapturingTransport(List<Float> values) {
			this.values = values;
		}

		@Override
		public List<Float> embed(String model, String text, EmbedContentConfig config) {
			this.model = model;
			this.text = text;
			this.config = config;
			calls++;
			return values;
		}
	}
}
