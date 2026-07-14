package shinhan.fibri.ieum.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingGateway;
import shinhan.fibri.ieum.ai.knowledge.accepted.AcceptedAnswerKnowledgeRepository;
import shinhan.fibri.ieum.ai.knowledge.accepted.service.AcceptedAnswerKnowledgeIngestionService;
import shinhan.fibri.ieum.ai.knowledge.accepted.service.AcceptedAnswerKnowledgeTaskLane;
import shinhan.fibri.ieum.ai.knowledge.embedding.KnowledgeDocumentEmbedder;
import shinhan.fibri.ieum.ai.knowledge.embedding.KnowledgeDocumentEmbeddingTextFormatter;

class AcceptedAnswerKnowledgeConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(AcceptedAnswerKnowledgeConfiguration.class);

	@Test
	void defaultsFeatureOffWithoutRequiringDatabaseOrGemini() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(AcceptedAnswerKnowledgeTaskLane.class);
			assertThat(context.getBean(AcceptedAnswerKnowledgeTaskLane.class).isEnabled()).isFalse();
			assertThat(context).doesNotHaveBean(AcceptedAnswerKnowledgeRepository.class);
			assertThat(context).doesNotHaveBean(AcceptedAnswerKnowledgeIngestionService.class);
			assertThat(context).doesNotHaveBean(KnowledgeDocumentEmbedder.class);
			assertThat(context).doesNotHaveBean(GeminiEmbeddingGateway.class);
		});
	}

	@Test
	void wiresAcceptedOnlyGeminiRepositoryEmbedderAndEnabledLane() {
		contextRunner
			.withBean(JdbcClient.class, () -> mock(JdbcClient.class))
			.withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
			.withPropertyValues(
				"app.ai.features.accepted-answer-ingestion-enabled=true",
				"app.ai.question-answer.embedding.gemini-api-key=test-only-not-a-real-key"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(GeminiEmbeddingGateway.class);
				assertThat(context).hasSingleBean(KnowledgeDocumentEmbeddingTextFormatter.class);
				assertThat(context).hasSingleBean(KnowledgeDocumentEmbedder.class);
				assertThat(context).hasSingleBean(AcceptedAnswerKnowledgeRepository.class);
				assertThat(context).hasSingleBean(AcceptedAnswerKnowledgeIngestionService.class);
				assertThat(context.getBean(AcceptedAnswerKnowledgeTaskLane.class).isEnabled()).isTrue();
			});
	}

	@Test
	void createsDedicatedSingleWorkerThirtyTwoQueueAbortPolicyExecutor() {
		ThreadPoolTaskExecutor executor =
			new AcceptedAnswerKnowledgeConfiguration().acceptedAnswerKnowledgeTaskExecutor();
		try {
			assertThat(executor.getCorePoolSize()).isOne();
			assertThat(executor.getMaxPoolSize()).isOne();
			assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(32);
			assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
				.isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
		}
		finally {
			executor.shutdown();
		}
	}

	@Test
	void locksMvpLeaseAttemptsAndSharedRedispatchDelayBounds() {
		AcceptedAnswerKnowledgeDispatchProperties defaults =
			new AcceptedAnswerKnowledgeDispatchProperties(
				false,
				Duration.ofMinutes(5),
				5,
				5
			);
		assertThat(defaults.enabled()).isFalse();
		assertThat(defaults.taskLease()).isEqualTo(Duration.ofMinutes(5));

		assertThatThrownBy(() -> new AcceptedAnswerKnowledgeDispatchProperties(
			true, Duration.ZERO, 5, 5
		)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease");
		assertThatThrownBy(() -> new AcceptedAnswerKnowledgeDispatchProperties(
			true, Duration.ofMinutes(5), 6, 5
		)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attempts");
		assertThatThrownBy(() -> new AcceptedAnswerKnowledgeDispatchProperties(
			true, Duration.ofMinutes(5), 5, 0
		)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("redispatch delay");
	}
}
