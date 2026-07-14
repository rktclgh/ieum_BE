package shinhan.fibri.ieum.ai.question.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.Client;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingGateway;
import shinhan.fibri.ieum.ai.embedding.GoogleGenAiGeminiEmbeddingGateway;

class QuestionEmbeddingConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(QuestionEmbeddingConfiguration.class);

	@Test
	void doesNotRegisterEmbeddingGatewayWhenQuestionAnswerIsDisabledByDefault() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(QuestionEmbeddingGateway.class);
			assertThat(context).doesNotHaveBean(GeminiEmbeddingGateway.class);
			assertThat(context).doesNotHaveBean(QuestionEmbeddingProperties.class);
			assertThat(context).doesNotHaveBean(Client.class);
		});
	}

	@Test
	void failsFastForBlankApiKeyWhenQuestionAnswerIsEnabled() {
		contextRunner
			.withPropertyValues(
				"app.ai.features.question-answer-enabled=true",
				"app.ai.question-answer.embedding.gemini-api-key="
			)
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void wiresGeminiEmbeddingGatewayWithTenSecondSingleAttemptTransportWhenEnabled() {
		contextRunner
			.withPropertyValues(validProperties())
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(QuestionEmbeddingGateway.class);
				assertThat(context).hasSingleBean(GeminiEmbeddingGateway.class);
				assertThat(context.getBean(GeminiEmbeddingGateway.class))
					.isInstanceOf(GoogleGenAiGeminiEmbeddingGateway.class);
				assertThat(context).hasBean("geminiEmbeddingClient");
				assertThat(context).hasSingleBean(Client.class);

				QuestionEmbeddingProperties properties = context.getBean(QuestionEmbeddingProperties.class);
				assertThat(properties.modelTimeout()).isEqualTo(Duration.ofSeconds(10));
				assertThat(properties.totalAttempts()).isOne();
			});
	}

	@Test
	void failsFastWhenEffectiveEmbeddingTimeoutIsNotTenSeconds() {
		contextRunner
			.withPropertyValues(validProperties())
			.withPropertyValues("app.ai.question-answer.embedding.model-timeout=11s")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void failsFastWhenEffectiveEmbeddingAttemptsAreNotOne() {
		contextRunner
			.withPropertyValues(validProperties())
			.withPropertyValues("app.ai.question-answer.embedding.total-attempts=2")
			.run(context -> assertThat(context).hasFailed());
	}

	private String[] validProperties() {
		return new String[] {
			"app.ai.features.question-answer-enabled=true",
			"app.ai.question-answer.embedding.gemini-api-key=test-only-not-a-real-key"
		};
	}
}
