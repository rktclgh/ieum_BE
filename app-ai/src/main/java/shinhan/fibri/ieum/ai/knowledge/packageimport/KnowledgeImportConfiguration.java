package shinhan.fibri.ieum.ai.knowledge.packageimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingGateway;
import shinhan.fibri.ieum.ai.knowledge.embedding.KnowledgeDocumentEmbedder;
import shinhan.fibri.ieum.ai.knowledge.embedding.KnowledgeDocumentEmbeddingTextFormatter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.ai", name = "mode", havingValue = "knowledge-import")
public class KnowledgeImportConfiguration {

	@Bean
	KnowledgeSeedPackageParser knowledgeSeedPackageParser(ObjectMapper objectMapper) {
		return new KnowledgeSeedPackageParser(objectMapper);
	}

	@Bean
	KnowledgeDocumentEmbeddingTextFormatter knowledgeDocumentEmbeddingTextFormatter() {
		return new KnowledgeDocumentEmbeddingTextFormatter();
	}

	@Bean
	KnowledgeDocumentEmbedder knowledgeDocumentEmbedder(
		KnowledgeDocumentEmbeddingTextFormatter formatter,
		GeminiEmbeddingGateway embeddingGateway
	) {
		return new KnowledgeDocumentEmbedder(formatter, embeddingGateway);
	}

	@Bean
	KnowledgeSeedPackagePreparer knowledgeSeedPackagePreparer(
		KnowledgeDocumentEmbedder documentEmbedder
	) {
		return new KnowledgeSeedPackagePreparer(documentEmbedder);
	}

	@Bean
	KnowledgeSeedPersistencePlanFactory knowledgeSeedPersistencePlanFactory(ObjectMapper objectMapper) {
		return new KnowledgeSeedPersistencePlanFactory(objectMapper);
	}

	@Bean
	JdbcKnowledgeSeedPackageStore knowledgeSeedPackageStore(
		JdbcClient jdbcClient,
		PlatformTransactionManager transactionManager,
		ObjectMapper objectMapper
	) {
		return new JdbcKnowledgeSeedPackageStore(jdbcClient, transactionManager, objectMapper);
	}

	@Bean
	KnowledgeSeedPackageImporter knowledgeSeedPackageImporter(
		KnowledgeSeedPackageParser parser,
		KnowledgeSeedPackagePreparer preparer,
		KnowledgeSeedPersistencePlanFactory planFactory,
		KnowledgeSeedPackageStore store
	) {
		return new KnowledgeSeedPackageImporter(parser, preparer, planFactory, store);
	}

	@Bean
	Resource knowledgeSeedPackageResource() {
		return new ClassPathResource("knowledge/korea_long_stay_seed_v0.2.json");
	}

	@Bean
	KnowledgeSeedImportRunner knowledgeSeedImportRunner(
		KnowledgeSeedPackageImporter importer,
		Resource knowledgeSeedPackageResource
	) {
		return new KnowledgeSeedImportRunner(importer, knowledgeSeedPackageResource);
	}
}
