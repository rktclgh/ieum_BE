package shinhan.fibri.ieum.ai.knowledge.packageimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingGateway;
import shinhan.fibri.ieum.ai.knowledge.embedding.KnowledgeDocumentEmbedder;
import shinhan.fibri.ieum.ai.knowledge.embedding.KnowledgeDocumentEmbeddingTextFormatter;

class KnowledgeImportConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(KnowledgeImportConfiguration.class, TestInfrastructure.class)
		.withPropertyValues("app.ai.mode=knowledge-import");

	@Test
	void wiresCompleteImportPipelineAroundCanonicalClasspathPackage() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(KnowledgeSeedPackageParser.class);
			assertThat(context).hasSingleBean(KnowledgeDocumentEmbeddingTextFormatter.class);
			assertThat(context).hasSingleBean(KnowledgeDocumentEmbedder.class);
			assertThat(context).hasSingleBean(KnowledgeSeedPackagePreparer.class);
			assertThat(context).hasSingleBean(KnowledgeSeedPersistencePlanFactory.class);
			assertThat(context).hasSingleBean(KnowledgeSeedPackageStore.class);
			assertThat(context).hasSingleBean(KnowledgeSeedPackageImporter.class);
			assertThat(context).hasSingleBean(KnowledgeSeedImportRunner.class);

			Resource resource = context.getBean("knowledgeSeedPackageResource", Resource.class);
			assertThat(resource.exists()).isTrue();
			assertThat(resource.getFilename()).isEqualTo("korea_long_stay_seed_v0.2.json");
		});
	}

	@Configuration(proxyBeanMethods = false)
	static class TestInfrastructure {

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper().findAndRegisterModules();
		}

		@Bean
		JdbcClient jdbcClient() {
			return mock(JdbcClient.class);
		}

		@Bean
		PlatformTransactionManager transactionManager() {
			return mock(PlatformTransactionManager.class);
		}

		@Bean
		GeminiEmbeddingGateway geminiEmbeddingGateway() {
			return mock(GeminiEmbeddingGateway.class);
		}
	}
}
