package shinhan.fibri.ieum.ai.question.grounding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import java.time.Clock;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerOutputParser;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerProperties;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.ai.features", name = "question-answer-enabled", havingValue = "true")
@EnableConfigurationProperties(LocalGroundingProperties.class)
public class LocalGroundingConfiguration {

	@Bean("bedrockLocalGroundingProvider")
	LocalGroundingProvider bedrockLocalGroundingProvider(
		ChatModel chatModel,
		LocalAnswerProperties answerProperties,
		LocalGroundingProperties groundingProperties
	) {
		return new BedrockNovaLocalGroundingProvider(
			chatModel,
			answerProperties,
			groundingProperties,
			Clock.systemUTC()
		);
	}

	@Bean("geminiLocalGroundingProvider")
	LocalGroundingProvider geminiLocalGroundingProvider(
		@Qualifier("localAnswerGeminiClient") Client geminiClient,
		LocalAnswerProperties answerProperties,
		LocalGroundingProperties groundingProperties
	) {
		return new GeminiLocalGroundingProvider(
			new GeminiLocalGroundingProvider.GoogleClient(geminiClient),
			answerProperties,
			groundingProperties,
			Clock.systemUTC()
		);
	}

	@Bean
	LocalGroundingGateway localGroundingGateway(
		@Qualifier("bedrockLocalGroundingProvider") LocalGroundingProvider primaryProvider,
		@Qualifier("geminiLocalGroundingProvider") LocalGroundingProvider fallbackProvider,
		ObjectMapper objectMapper,
		LocalAnswerOutputParser repairOutputParser,
		LocalGroundingProperties properties
	) {
		return new FallbackLocalGroundingGateway(
			primaryProvider,
			fallbackProvider,
			new GroundingValidationPromptFactory(objectMapper),
			new GroundingRepairPromptFactory(objectMapper),
			new GroundingValidationOutputParser(objectMapper),
			repairOutputParser,
			properties
		);
	}
}
