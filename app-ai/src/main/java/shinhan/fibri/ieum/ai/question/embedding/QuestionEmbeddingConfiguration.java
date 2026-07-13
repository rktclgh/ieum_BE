package shinhan.fibri.ieum.ai.question.embedding;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.ai.features", name = "question-answer-enabled", havingValue = "true")
@EnableConfigurationProperties(QuestionEmbeddingProperties.class)
class QuestionEmbeddingConfiguration {

	@Bean(destroyMethod = "close")
	Client questionEmbeddingGeminiClient(QuestionEmbeddingProperties properties) {
		return Client.builder()
			.apiKey(properties.geminiApiKey())
			.httpOptions(geminiHttpOptions(properties))
			.build();
	}

	@Bean
	QuestionEmbeddingGateway questionEmbeddingGateway(
		@Qualifier("questionEmbeddingGeminiClient") Client questionEmbeddingGeminiClient
	) {
		return new GeminiQuestionEmbeddingGateway(new GoogleGenAiGeminiEmbeddingClient(questionEmbeddingGeminiClient));
	}

	static HttpOptions geminiHttpOptions(QuestionEmbeddingProperties properties) {
		Objects.requireNonNull(properties, "properties must not be null");
		return HttpOptions.builder()
			.timeout(Math.toIntExact(properties.modelTimeout().toMillis()))
			.retryOptions(HttpRetryOptions.builder()
				.attempts(properties.totalAttempts())
				.httpStatusCodes(List.of())
				.build())
			.build();
	}
}
