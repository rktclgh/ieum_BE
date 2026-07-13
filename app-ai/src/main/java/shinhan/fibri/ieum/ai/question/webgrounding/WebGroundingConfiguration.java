package shinhan.fibri.ieum.ai.question.webgrounding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
	prefix = "app.ai.features",
	name = "question-answer-enabled",
	havingValue = "true"
)
@ConditionalOnProperty(
	prefix = "app.ai.features",
	name = "web-grounding-enabled",
	havingValue = "true"
)
@EnableConfigurationProperties(WebGroundingProperties.class)
public class WebGroundingConfiguration {

	@Bean(destroyMethod = "close")
	Client webGroundingGeminiClient(WebGroundingProperties properties) {
		return Client.builder()
			.apiKey(properties.geminiApiKey())
			.httpOptions(geminiHttpOptions(properties))
			.build();
	}

	@Bean
	GeminiWebGroundingClient geminiWebGroundingClient(
		@Qualifier("webGroundingGeminiClient") Client client
	) {
		return new GeminiGoogleGenAiWebGroundingClient(client);
	}

	@Bean
	GeminiWebGroundingModelPromptFactory geminiWebGroundingModelPromptFactory(
		ObjectMapper objectMapper
	) {
		return new GeminiWebGroundingModelPromptFactory(objectMapper);
	}

	@Bean
	PublicWebSourceUriValidator publicWebSourceUriValidator() {
		return new PublicWebSourceUriValidator();
	}

	@Bean
	MaterialClaimCoverageValidator materialClaimCoverageValidator() {
		return new MaterialClaimCoverageValidator();
	}

	@Bean
	GeminiWebGroundingResponseParser geminiWebGroundingResponseParser(
		PublicWebSourceUriValidator uriValidator,
		MaterialClaimCoverageValidator coverageValidator
	) {
		return new GeminiWebGroundingResponseParser(uriValidator, coverageValidator);
	}

	@Bean
	WebGroundingGateway webGroundingGateway(
		GeminiWebGroundingClient client,
		GeminiWebGroundingModelPromptFactory promptFactory,
		GeminiWebGroundingResponseParser parser,
		WebGroundingProperties properties
	) {
		return new GeminiSearchWebGroundingGateway(
			client,
			promptFactory,
			parser,
			properties,
			Clock.systemUTC()
		);
	}

	static HttpOptions geminiHttpOptions(WebGroundingProperties properties) {
		Objects.requireNonNull(properties, "properties must not be null");
		return HttpOptions.builder()
			.timeout(Math.toIntExact(properties.modelTimeout().toMillis()))
			.retryOptions(HttpRetryOptions.builder()
				.attempts(1)
				.httpStatusCodes(List.of())
				.build())
			.build();
	}
}
