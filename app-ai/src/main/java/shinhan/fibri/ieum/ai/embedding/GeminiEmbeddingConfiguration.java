package shinhan.fibri.ieum.ai.embedding;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import shinhan.fibri.ieum.ai.question.embedding.QuestionEmbeddingProperties;

@Configuration(proxyBeanMethods = false)
@Conditional(GeminiEmbeddingConfiguration.AnyEmbeddingConsumerEnabledCondition.class)
@EnableConfigurationProperties(QuestionEmbeddingProperties.class)
public class GeminiEmbeddingConfiguration {

	@Bean(destroyMethod = "close")
	Client geminiEmbeddingClient(QuestionEmbeddingProperties properties) {
		return Client.builder()
			.apiKey(properties.geminiApiKey())
			.httpOptions(geminiHttpOptions(properties))
			.build();
	}

	@Bean
	GeminiEmbeddingGateway geminiEmbeddingGateway(
		@Qualifier("geminiEmbeddingClient") Client geminiEmbeddingClient
	) {
		return new GoogleGenAiGeminiEmbeddingGateway(geminiEmbeddingClient);
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

	static final class AnyEmbeddingConsumerEnabledCondition extends AnyNestedCondition {

		AnyEmbeddingConsumerEnabledCondition() {
			super(ConfigurationPhase.REGISTER_BEAN);
		}

		@ConditionalOnProperty(
			prefix = "app.ai.features",
			name = "question-answer-enabled",
			havingValue = "true"
		)
		static final class QuestionAnswerEnabled {
		}

		@ConditionalOnProperty(
			prefix = "app.ai.features",
			name = "accepted-answer-ingestion-enabled",
			havingValue = "true"
		)
		static final class AcceptedAnswerIngestionEnabled {
		}

		@ConditionalOnProperty(
			prefix = "app.ai",
			name = "mode",
			havingValue = "knowledge-import"
		)
		static final class KnowledgeImportMode {
		}
	}
}
