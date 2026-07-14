package shinhan.fibri.ieum.ai.config;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import java.util.Objects;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import shinhan.fibri.ieum.ai.report.service.BedrockReportReviewModelProvider;
import shinhan.fibri.ieum.ai.report.service.FallbackReportReviewModelGateway;
import shinhan.fibri.ieum.ai.report.service.GeminiReportReviewModelProvider;
import shinhan.fibri.ieum.ai.report.service.ReportReviewModelGateway;
import shinhan.fibri.ieum.ai.report.service.ReportReviewModelOutputParser;
import shinhan.fibri.ieum.ai.report.service.ReportReviewModelPromptFactory;
import shinhan.fibri.ieum.ai.report.service.ReportReviewModelProvider;

@Configuration
@ConditionalOnProperty(prefix = "app.ai.features", name = "report-review-enabled", havingValue = "true")
@EnableConfigurationProperties(ReportModelProperties.class)
public class ReportReviewModelConfiguration {

	@Bean
	ReportReviewModelPromptFactory reportReviewModelPromptFactory() {
		return new ReportReviewModelPromptFactory();
	}

	@Bean
	ReportReviewModelOutputParser reportReviewModelOutputParser() {
		return new ReportReviewModelOutputParser();
	}

	@Bean(destroyMethod = "close")
	Client reportReviewGeminiClient(ReportModelProperties properties) {
		return Client.builder()
			.apiKey(properties.geminiApiKey())
			.httpOptions(HttpOptions.builder().timeout(timeoutMillis(properties)).build())
			.build();
	}

	@Bean("bedrockReportReviewModelProvider")
	BedrockReportReviewModelProvider bedrockReportReviewModelProvider(
		ChatModel chatModel,
		ReportReviewModelPromptFactory promptFactory,
		ReportReviewModelOutputParser outputParser,
		ReportModelProperties properties
	) {
		return new BedrockReportReviewModelProvider(chatModel, promptFactory, outputParser, properties);
	}

	@Bean("geminiReportReviewModelProvider")
	GeminiReportReviewModelProvider geminiReportReviewModelProvider(
		Client reportReviewGeminiClient,
		ReportReviewModelPromptFactory promptFactory,
		ReportReviewModelOutputParser outputParser,
		ReportModelProperties properties
	) {
		return GeminiReportReviewModelProvider.googleGenAi(
			reportReviewGeminiClient,
			properties.geminiModel(),
			promptFactory,
			outputParser
		);
	}

	@Bean
	ReportReviewModelGateway reportReviewModelGateway(
		@Qualifier("bedrockReportReviewModelProvider") ReportReviewModelProvider primaryProvider,
		@Qualifier("geminiReportReviewModelProvider") ReportReviewModelProvider fallbackProvider,
		ReportModelProperties properties
	) {
		return new FallbackReportReviewModelGateway(primaryProvider, fallbackProvider, properties.promptVersion());
	}

	private int timeoutMillis(ReportModelProperties properties) {
		Objects.requireNonNull(properties, "properties must not be null");
		return Math.toIntExact(properties.modelTimeout().toMillis());
	}
}
