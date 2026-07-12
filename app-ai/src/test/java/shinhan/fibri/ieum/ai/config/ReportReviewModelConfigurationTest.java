package shinhan.fibri.ieum.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.Client;
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
import shinhan.fibri.ieum.ai.report.service.BedrockReportReviewModelProvider;
import shinhan.fibri.ieum.ai.report.service.FallbackReportReviewModelGateway;
import shinhan.fibri.ieum.ai.report.service.GeminiReportReviewModelProvider;
import shinhan.fibri.ieum.ai.report.service.ReportReviewModelGateway;

class ReportReviewModelConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
		.withUserConfiguration(ReportReviewModelConfiguration.class, ChatModelTestConfiguration.class);

	@Test
	void doesNotCreateModelClientsWhenReportReviewIsDisabled() {
		contextRunner.run(context -> {
			assertThat(context).doesNotHaveBean(ReportReviewModelGateway.class);
			assertThat(context).doesNotHaveBean(Client.class);
		});
	}

	@Test
	void wiresNovaPrimaryGeminiFallbackAndTheConfiguredPromptVersion() {
		contextRunner
			.withPropertyValues(
				"app.ai.features.report-review-enabled=true",
				"app.ai.report.gemini-api-key=test-gemini-key",
				"app.ai.report.gemini-model=gemini-3.1-flash-lite",
				"app.ai.report.nova-model=amazon.nova-lite-v1:0",
				"app.ai.report.bedrock-region=ap-southeast-2",
				"app.ai.report.model-timeout=30s",
				"app.ai.report.prompt-version=report-review-v1"
			)
			.run(context -> {
				assertThat(context).hasSingleBean(Client.class);
				assertThat(context).hasSingleBean(BedrockReportReviewModelProvider.class);
				assertThat(context).hasSingleBean(GeminiReportReviewModelProvider.class);
				assertThat(context).hasSingleBean(ReportReviewModelGateway.class);
				assertThat(context.getBean(ReportReviewModelGateway.class)).isInstanceOf(FallbackReportReviewModelGateway.class);
			});
	}

	@Configuration
	static class ChatModelTestConfiguration {

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
