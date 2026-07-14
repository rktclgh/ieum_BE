package shinhan.fibri.ieum.ai.knowledge.packageimport;

import org.springframework.ai.model.bedrock.cohere.autoconfigure.BedrockCohereEmbeddingAutoConfiguration;
import org.springframework.ai.model.bedrock.converse.autoconfigure.BedrockConverseProxyChatAutoConfiguration;
import org.springframework.ai.model.bedrock.titan.autoconfigure.BedrockTitanEmbeddingAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import shinhan.fibri.ieum.ai.config.AiDatabaseCapabilityVerifier;
import shinhan.fibri.ieum.ai.config.JsonConfig;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingConfiguration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.ai", name = "mode", havingValue = "knowledge-import")
@EnableAutoConfiguration(exclude = {
	BedrockCohereEmbeddingAutoConfiguration.class,
	BedrockConverseProxyChatAutoConfiguration.class,
	BedrockTitanEmbeddingAutoConfiguration.class,
	DataJpaRepositoriesAutoConfiguration.class,
	HibernateJpaAutoConfiguration.class
})
@Import({
	JsonConfig.class,
	AiDatabaseCapabilityVerifier.class,
	GeminiEmbeddingConfiguration.class,
	KnowledgeImportConfiguration.class
})
public class KnowledgeImportApplicationConfiguration {
}
