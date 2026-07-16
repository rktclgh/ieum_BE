package shinhan.fibri.ieum.ai.question.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import shinhan.fibri.ieum.ai.config.GeminiApiKeyProperties;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.ai.features", name = "question-answer-enabled", havingValue = "true")
@EnableConfigurationProperties({
	LocalAnswerProperties.class,
	GeminiApiKeyProperties.class
})
public class LocalAnswerConfiguration {

	@Bean
	LocalAnswerPromptFactory localAnswerPromptFactory(ObjectMapper objectMapper) {
		return new LocalAnswerPromptFactory(objectMapper);
	}

	@Bean
	LocalAnswerOutputParser localAnswerOutputParser(ObjectMapper objectMapper) {
		return new LocalAnswerOutputParser(objectMapper);
	}

	@Bean(destroyMethod = "close")
	Client localAnswerGeminiClient(
		LocalAnswerProperties properties,
		GeminiApiKeyProperties geminiApiKeyProperties
	) {
		return Client.builder()
			.apiKey(geminiApiKeyProperties.geminiApiKey())
			.httpOptions(geminiHttpOptions(properties))
			.build();
	}

	@Bean("bedrockLocalAnswerProvider")
	LocalAnswerProvider bedrockLocalAnswerProvider(
		ChatModel chatModel,
		LocalAnswerPromptFactory promptFactory,
		LocalAnswerProperties properties
	) {
		return new BedrockNovaLocalAnswerProvider(
			chatModel,
			promptFactory,
			properties,
			Clock.systemUTC()
		);
	}

	@Bean("geminiLocalAnswerProvider")
	LocalAnswerProvider geminiLocalAnswerProvider(
		@Qualifier("localAnswerGeminiClient") Client geminiClient,
		LocalAnswerPromptFactory promptFactory,
		LocalAnswerProperties properties
	) {
		return new GeminiLocalAnswerProvider(
			new GeminiGoogleGenAiLocalAnswerClient(geminiClient),
			promptFactory,
			properties,
			Clock.systemUTC()
		);
	}

	@Bean
	LocalAnswerGateway localAnswerGateway(
		@Qualifier("bedrockLocalAnswerProvider") LocalAnswerProvider primaryProvider,
		@Qualifier("geminiLocalAnswerProvider") LocalAnswerProvider fallbackProvider,
		LocalAnswerOutputParser outputParser,
		LocalAnswerProperties properties
	) {
		return new FallbackLocalAnswerGateway(
			primaryProvider,
			fallbackProvider,
			outputParser,
			properties
		);
	}

	static HttpOptions geminiHttpOptions(LocalAnswerProperties properties) {
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
