package shinhan.fibri.ieum.ai.knowledge.packageimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import shinhan.fibri.ieum.ai.config.AiDatabaseCapabilityVerifier;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingGateway;
import shinhan.fibri.ieum.ai.question.callback.QuestionCompletionCallbackRecoveryScheduler;
import shinhan.fibri.ieum.ai.question.controller.QuestionAnswerJobDispatchInternalController;
import shinhan.fibri.ieum.ai.question.embedding.QuestionEmbeddingGateway;
import shinhan.fibri.ieum.ai.question.service.QuestionTaskRecoveryScheduler;
import shinhan.fibri.ieum.ai.report.controller.ReportReviewInternalController;

class KnowledgeImportApplicationConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(KnowledgeImportApplicationConfiguration.class, TestDatabaseInfrastructure.class)
		.withPropertyValues(
			"app.ai.mode=knowledge-import",
			"app.ai.features.question-answer-enabled=true",
			"app.ai.features.report-review-enabled=true",
			"app.ai.question-answer.embedding.gemini-api-key=test-only-not-a-real-key",
			"app.ai.database.embedding-dimensions=768",
			"app.ai.database.required-extensions=vector,postgis,pgcrypto"
		);

	@Test
	void createsOnlyKnowledgeImportSurfaceEvenWhenServerFeaturesAreEnabled() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(AiDatabaseCapabilityVerifier.class);
			assertThat(context).hasSingleBean(GeminiEmbeddingGateway.class);
			assertThat(context).hasSingleBean(KnowledgeSeedPackageImporter.class);
			assertThat(context).hasSingleBean(KnowledgeSeedImportRunner.class);

			assertThat(context).doesNotHaveBean(QuestionEmbeddingGateway.class);
			assertThat(context).doesNotHaveBean(QuestionAnswerJobDispatchInternalController.class);
			assertThat(context).doesNotHaveBean(ReportReviewInternalController.class);
			assertThat(context).doesNotHaveBean(QuestionTaskRecoveryScheduler.class);
			assertThat(context).doesNotHaveBean(QuestionCompletionCallbackRecoveryScheduler.class);
			assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
			assertThat(context).doesNotHaveBean(BedrockProxyChatModel.class);
		});
	}

	@Configuration(proxyBeanMethods = false)
	static class TestDatabaseInfrastructure {

		@Bean
		DataSource dataSource() {
			return mock(DataSource.class);
		}

		@Bean
		JdbcClient jdbcClient() {
			return mock(JdbcClient.class);
		}

		@Bean
		PlatformTransactionManager transactionManager() {
			return mock(PlatformTransactionManager.class);
		}
	}
}
