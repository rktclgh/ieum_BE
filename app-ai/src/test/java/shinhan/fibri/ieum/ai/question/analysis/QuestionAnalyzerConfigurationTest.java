package shinhan.fibri.ieum.ai.question.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class QuestionAnalyzerConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
		.withUserConfiguration(QuestionAnalyzerConfiguration.class);

	@Test
	void doesNotCreateAnalyzerBeansWhenQuestionAnswerIsDisabled() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(QuestionQueryAnalyzer.class);
			assertThat(context).doesNotHaveBean(QuestionAnalyzerProperties.class);
		});
	}

	@Test
	void wiresTheAnalyzerWhenEnabledWithValidConfiguration() {
		contextRunner
			.withUserConfiguration(AnalyzerDependencies.class)
			.withPropertyValues(validProperties())
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(QuestionQueryAnalyzer.class);
				assertThat(context).hasSingleBean(QuestionAnalyzerProperties.class);
				assertThat(context.getBean(QuestionQueryAnalyzer.class))
					.isInstanceOf(BedrockNovaQuestionQueryAnalyzer.class);
			});
	}

	@Test
	void failsFastWhenEnabledWithoutAChatModel() {
		contextRunner
			.withUserConfiguration(ObjectMapperOnly.class)
			.withPropertyValues(validProperties())
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void failsFastWhenEnabledConfigurationIsMissing() {
		contextRunner
			.withUserConfiguration(AnalyzerDependencies.class)
			.withPropertyValues("app.ai.features.question-answer-enabled=true")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void failsFastWhenMaxTokensIsOutsideTheBoundedRange() {
		for (int maxTokens : List.of(127, 2049)) {
			contextRunner
				.withUserConfiguration(AnalyzerDependencies.class)
				.withPropertyValues(
					"app.ai.features.question-answer-enabled=true",
					"app.ai.question-answer.analyzer.model=amazon.nova-micro-v1:0",
					"app.ai.question-answer.analyzer.analysis-version=question-query-analysis-v1",
					"app.ai.question-answer.analyzer.max-tokens=" + maxTokens
				)
				.run(context -> assertThat(context).hasFailed());
		}
	}

	@Test
	void failsFastWhenQuestionBedrockTransportViolatesTheSydneyContract() {
		for (String invalidProperty : List.of(
			"app.ai.question-answer.analyzer.bedrock-region=ap-northeast-2",
			"app.ai.question-answer.analyzer.model-timeout=31s"
		)) {
			contextRunner
				.withUserConfiguration(AnalyzerDependencies.class)
				.withPropertyValues(validProperties())
				.withPropertyValues(invalidProperty)
				.run(context -> assertThat(context).hasFailed());
		}
	}

	@Test
	void failsFastWhenTheEffectiveSpringAiTransportOverridesTheValidatedContract() {
		for (String invalidProperty : List.of(
			"spring.ai.bedrock.aws.region=ap-northeast-2",
			"spring.ai.bedrock.aws.timeout=31s"
		)) {
			contextRunner
				.withUserConfiguration(AnalyzerDependencies.class)
				.withPropertyValues(validProperties())
				.withPropertyValues(invalidProperty)
				.run(context -> assertThat(context).hasFailed());
		}
	}

	@Test
	void failsFastWhenAnalysisVersionCannotFitTheCheckpointColumn() {
		contextRunner
			.withUserConfiguration(AnalyzerDependencies.class)
			.withPropertyValues(validProperties())
			.withPropertyValues(
				"app.ai.question-answer.analyzer.analysis-version=" + "v".repeat(81)
			)
			.run(context -> assertThat(context).hasFailed());
	}

	private String[] validProperties() {
		return new String[] {
			"app.ai.features.question-answer-enabled=true",
			"app.ai.question-answer.analyzer.model=amazon.nova-micro-v1:0",
			"app.ai.question-answer.analyzer.analysis-version=question-query-analysis-v1",
			"app.ai.question-answer.analyzer.max-tokens=512",
			"app.ai.question-answer.analyzer.bedrock-region=ap-southeast-2",
			"app.ai.question-answer.analyzer.model-timeout=30s",
			"spring.ai.bedrock.aws.region=ap-southeast-2",
			"spring.ai.bedrock.aws.timeout=30s"
		};
	}

	@Configuration
	static class ObjectMapperOnly {

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}
	}

	@Configuration
	static class AnalyzerDependencies {

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}

		@Bean
		ChatModel chatModel() {
			return new ChatModel() {
				@Override
				public ChatResponse call(Prompt prompt) {
					return new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))));
				}
			};
		}
	}
}
