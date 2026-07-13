package shinhan.fibri.ieum.ai.question.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalAnswerConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(LocalAnswerConfiguration.class)
		.withBean(ObjectMapper.class, ObjectMapper::new)
		.withBean(ChatModel.class, () -> prompt ->
			new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))))
		);

	@Test
	void featureOffCreatesNoGenerationBeansAndRequiresNoSecret() {
		contextRunner
			.withPropertyValues("app.ai.features.question-answer-enabled=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(LocalAnswerGateway.class);
				assertThat(context).doesNotHaveBean(LocalAnswerProperties.class);
				assertThat(context).doesNotHaveBean(Client.class);
			});
	}

	@Test
	void featureOnWiresBothProvidersAndUsesAThirtySecondNoRetryGeminiTransport() {
		contextRunner
			.withPropertyValues(validProperties())
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(LocalAnswerGateway.class);
				assertThat(context).hasSingleBean(LocalAnswerProperties.class);
				LocalAnswerProperties properties = context.getBean(LocalAnswerProperties.class);
				assertThat(properties.maxTokens()).isEqualTo(1024);
				assertThat(properties.modelTimeout()).isEqualTo(Duration.ofSeconds(30));
				HttpOptions options = LocalAnswerConfiguration.geminiHttpOptions(properties);
				assertThat(options.timeout()).contains(30_000);
				assertThat(options.retryOptions()).isPresent();
				assertThat(options.retryOptions().orElseThrow().attempts()).contains(1);
				assertThat(options.retryOptions().orElseThrow().httpStatusCodes()).contains(List.of());
			});
	}

	@Test
	void featureOnFailsFastForMissingKeyOrWrongTimeout() {
		contextRunner
			.withPropertyValues(
				"app.ai.features.question-answer-enabled=true",
				"app.ai.question-answer.generation.primary-model=amazon.nova-micro-v1:0",
				"app.ai.question-answer.generation.fallback-model=gemini-3.1-flash-lite",
				"app.ai.question-answer.generation.gemini-api-key=",
				"app.ai.question-answer.generation.prompt-version=question-local-answer-v1",
				"app.ai.question-answer.generation.model-timeout=30s"
			)
			.run(context -> assertThat(context).hasFailed());

		contextRunner
			.withPropertyValues(validProperties())
			.withPropertyValues("app.ai.question-answer.generation.model-timeout=31s")
			.run(context -> assertThat(context).hasFailed());
	}

	private String[] validProperties() {
		return new String[] {
			"app.ai.features.question-answer-enabled=true",
			"app.ai.question-answer.generation.primary-model=amazon.nova-micro-v1:0",
			"app.ai.question-answer.generation.fallback-model=gemini-3.1-flash-lite",
			"app.ai.question-answer.generation.gemini-api-key=test-key",
			"app.ai.question-answer.generation.prompt-version=question-local-answer-v1",
			"app.ai.question-answer.generation.model-timeout=30s"
		};
	}
}
