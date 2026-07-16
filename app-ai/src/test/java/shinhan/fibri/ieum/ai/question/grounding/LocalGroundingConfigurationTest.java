package shinhan.fibri.ieum.ai.question.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerConfiguration;

class LocalGroundingConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(LocalAnswerConfiguration.class, LocalGroundingConfiguration.class)
		.withBean(ObjectMapper.class, ObjectMapper::new)
		.withBean(ChatModel.class, () -> prompt ->
			new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))))
		);

	@Test
	void featureOffCreatesNoGroundingBeansAndRequiresNoSecrets() {
		contextRunner
			.withPropertyValues("app.ai.features.question-answer-enabled=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(LocalGroundingGateway.class);
				assertThat(context).doesNotHaveBean(LocalGroundingProperties.class);
				assertThat(context).doesNotHaveBean(Client.class);
			});
	}

	@Test
	void featureOnWiresOneGatewayAndReusesTheSingleGenerationGeminiClient() {
		contextRunner
			.withPropertyValues(validProperties())
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(LocalGroundingGateway.class);
				assertThat(context).hasSingleBean(LocalGroundingProperties.class);
				assertThat(context).hasSingleBean(Client.class);
				LocalGroundingProperties properties = context.getBean(LocalGroundingProperties.class);
				assertThat(properties.validationPromptVersion())
					.isEqualTo("question-grounding-validation-v1");
				assertThat(properties.repairPromptVersion())
					.isEqualTo("question-grounding-repair-v1");
				assertThat(properties.validationMaxTokens()).isEqualTo(512);
				assertThat(properties.repairMaxTokens()).isEqualTo(1024);
				assertThat(properties.modelTimeout()).isEqualTo(Duration.ofSeconds(30));
			});
	}

	@Test
	void featureOnFailsFastForWrongPromptVersionTokenBudgetsOrTimeout() {
		contextRunner
			.withPropertyValues(validProperties())
			.withPropertyValues(
				"app.ai.question-answer.grounding.repair-prompt-version=question-grounding-repair-v2"
			)
			.run(context -> assertThat(context).hasFailed());

		contextRunner
			.withPropertyValues(validProperties())
			.withPropertyValues("app.ai.question-answer.grounding.validation-max-tokens=513")
			.run(context -> assertThat(context).hasFailed());

		contextRunner
			.withPropertyValues(validProperties())
			.withPropertyValues("app.ai.question-answer.grounding.repair-max-tokens=1023")
			.run(context -> assertThat(context).hasFailed());

		contextRunner
			.withPropertyValues(validProperties())
			.withPropertyValues("app.ai.question-answer.grounding.model-timeout=31s")
			.run(context -> assertThat(context).hasFailed());
	}

	private String[] validProperties() {
		return new String[] {
			"app.ai.features.question-answer-enabled=true",
			"app.ai.question-answer.generation.primary-model=amazon.nova-micro-v1:0",
			"app.ai.question-answer.generation.fallback-model=gemini-3.1-flash-lite",
			"app.ai.gemini-api-key=test-key",
			"app.ai.question-answer.generation.prompt-version=question-local-answer-v1",
			"app.ai.question-answer.generation.max-tokens=1024",
			"app.ai.question-answer.generation.model-timeout=30s",
			"app.ai.question-answer.grounding.validation-prompt-version=question-grounding-validation-v1",
			"app.ai.question-answer.grounding.repair-prompt-version=question-grounding-repair-v1",
			"app.ai.question-answer.grounding.validation-max-tokens=512",
			"app.ai.question-answer.grounding.repair-max-tokens=1024",
			"app.ai.question-answer.grounding.model-timeout=30s"
		};
	}
}
