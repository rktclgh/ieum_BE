package shinhan.fibri.ieum.ai.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import shinhan.fibri.ieum.ai.question.embedding.QuestionEmbeddingProperties;

class GeminiEmbeddingConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(GeminiEmbeddingConfiguration.class);

	@Test
	void doesNotRegisterSharedEmbeddingTransportByDefault() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(GeminiEmbeddingGateway.class);
			assertThat(context).doesNotHaveBean(QuestionEmbeddingProperties.class);
			assertThat(context).doesNotHaveBean(Client.class);
		});
	}

	@Test
	void wiresSharedEmbeddingTransportForKnowledgeImportMode() {
		contextRunner
			.withPropertyValues(
				"app.ai.mode=knowledge-import",
				"app.ai.gemini-api-key=test-only-not-a-real-key"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(GeminiEmbeddingGateway.class);
				assertThat(context.getBean(GeminiEmbeddingGateway.class))
					.isInstanceOf(GoogleGenAiGeminiEmbeddingGateway.class);
				assertThat(context).hasBean("geminiEmbeddingClient");
				assertThat(context).hasSingleBean(Client.class);

				QuestionEmbeddingProperties properties = context.getBean(QuestionEmbeddingProperties.class);
				assertThat(properties.modelTimeout()).isEqualTo(Duration.ofSeconds(10));
				assertThat(properties.totalAttempts()).isOne();

				HttpOptions httpOptions = GeminiEmbeddingConfiguration.geminiHttpOptions(properties);
				assertThat(httpOptions.timeout()).contains(10_000);
				assertThat(httpOptions.retryOptions()).isPresent();
				assertThat(httpOptions.retryOptions().orElseThrow().attempts()).contains(1);
				assertThat(httpOptions.retryOptions().orElseThrow().httpStatusCodes()).contains(List.of());
			});
	}

	@Test
	void wiresSharedEmbeddingTransportForQuestionAnswerMode() {
		contextRunner
			.withPropertyValues(
				"app.ai.features.question-answer-enabled=true",
				"app.ai.gemini-api-key=test-only-not-a-real-key"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(GeminiEmbeddingGateway.class);
			});
	}

	@Test
	void wiresSharedEmbeddingTransportForAcceptedAnswerIngestionAlone() {
		contextRunner
			.withPropertyValues(
				"app.ai.features.accepted-answer-ingestion-enabled=true",
				"app.ai.gemini-api-key=test-only-not-a-real-key"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(GeminiEmbeddingGateway.class);
			});
	}

	@Test
	void failsFastForBlankApiKeyInKnowledgeImportMode() {
		contextRunner
			.withPropertyValues(
				"app.ai.mode=knowledge-import",
				"app.ai.gemini-api-key="
			)
			.run(context -> assertThat(context).hasFailed());
	}
}
