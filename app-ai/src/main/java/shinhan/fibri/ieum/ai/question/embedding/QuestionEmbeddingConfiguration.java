package shinhan.fibri.ieum.ai.question.embedding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingGateway;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingConfiguration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.ai.features", name = "question-answer-enabled", havingValue = "true")
@Import(GeminiEmbeddingConfiguration.class)
class QuestionEmbeddingConfiguration {

	@Bean
	QuestionEmbeddingGateway questionEmbeddingGateway(GeminiEmbeddingGateway geminiEmbeddingGateway) {
		return new GeminiQuestionEmbeddingGateway(geminiEmbeddingGateway);
	}
}
