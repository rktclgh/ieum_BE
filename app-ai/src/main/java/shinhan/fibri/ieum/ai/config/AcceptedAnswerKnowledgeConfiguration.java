package shinhan.fibri.ieum.ai.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingConfiguration;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingGateway;
import shinhan.fibri.ieum.ai.knowledge.accepted.AcceptedAnswerKnowledgeDocumentFactory;
import shinhan.fibri.ieum.ai.knowledge.accepted.AcceptedAnswerKnowledgeRepository;
import shinhan.fibri.ieum.ai.knowledge.accepted.JdbcAcceptedAnswerKnowledgeRepository;
import shinhan.fibri.ieum.ai.knowledge.accepted.service.AcceptedAnswerKnowledgeIngestionService;
import shinhan.fibri.ieum.ai.knowledge.accepted.service.AcceptedAnswerKnowledgeTaskLane;
import shinhan.fibri.ieum.ai.knowledge.embedding.KnowledgeDocumentEmbedder;
import shinhan.fibri.ieum.ai.knowledge.embedding.KnowledgeDocumentEmbeddingTextFormatter;
import shinhan.fibri.ieum.ai.question.analysis.StoredAddressRegionParser;
import shinhan.fibri.ieum.ai.question.webgrounding.WebQuestionPiiSanitizer;

@Configuration(proxyBeanMethods = false)
@Import({GeminiEmbeddingConfiguration.class, AcceptedAnswerKnowledgeEnabledConfiguration.class})
public class AcceptedAnswerKnowledgeConfiguration {

	private static final int QUEUE_CAPACITY = 32;

	@Bean
	AcceptedAnswerKnowledgeDispatchProperties acceptedAnswerKnowledgeDispatchProperties(
		@Value("${app.ai.features.accepted-answer-ingestion-enabled:false}") boolean enabled,
		@Value("${app.ai.accepted-answer.task-lease:5m}") String taskLease,
		@Value("${app.ai.accepted-answer.max-attempts:5}") int maxAttempts,
		@Value("${app.ai.accepted-answer.redispatch-delay-seconds:5}") int redispatchDelaySeconds
	) {
		return new AcceptedAnswerKnowledgeDispatchProperties(
			enabled,
			DurationStyle.detectAndParse(taskLease),
			maxAttempts,
			redispatchDelaySeconds
		);
	}

	@Bean("acceptedAnswerKnowledgeTaskExecutor")
	public ThreadPoolTaskExecutor acceptedAnswerKnowledgeTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("ieum-accepted-answer-");
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(QUEUE_CAPACITY);
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(10);
		executor.initialize();
		return executor;
	}

	@Bean
	AcceptedAnswerKnowledgeTaskLane acceptedAnswerKnowledgeTaskLane(
		AcceptedAnswerKnowledgeDispatchProperties properties,
		@Qualifier("acceptedAnswerKnowledgeTaskExecutor") ThreadPoolTaskExecutor executor,
		ObjectProvider<AcceptedAnswerKnowledgeIngestionService> processorProvider
	) {
		AcceptedAnswerKnowledgeIngestionService processor = processorProvider.getIfAvailable();
		if (properties.enabled() && processor == null) {
			throw new IllegalStateException(
				"Accepted answer ingestion requires its exact-ID processor"
			);
		}
		return new AcceptedAnswerKnowledgeTaskLane(
			properties.enabled(),
			executor,
			processor == null ? answerId -> { } : processor::process
		);
	}
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
	prefix = "app.ai.features",
	name = "accepted-answer-ingestion-enabled",
	havingValue = "true"
)
class AcceptedAnswerKnowledgeEnabledConfiguration {

	@Bean
	AcceptedAnswerKnowledgeDocumentFactory acceptedAnswerKnowledgeDocumentFactory() {
		return new AcceptedAnswerKnowledgeDocumentFactory(
			new WebQuestionPiiSanitizer(),
			new StoredAddressRegionParser()
		);
	}

	@Bean
	AcceptedAnswerKnowledgeRepository acceptedAnswerKnowledgeRepository(
		JdbcClient jdbcClient,
		PlatformTransactionManager transactionManager,
		AcceptedAnswerKnowledgeDocumentFactory documentFactory,
		@Value("${app.ai.features.knowledge-relation-extraction-enabled:false}") boolean enqueueRelationExtractionTasks
	) {
		return new JdbcAcceptedAnswerKnowledgeRepository(
			jdbcClient,
			transactionManager,
			documentFactory,
			enqueueRelationExtractionTasks
		);
	}

	@Bean
	KnowledgeDocumentEmbeddingTextFormatter acceptedAnswerKnowledgeEmbeddingTextFormatter() {
		return new KnowledgeDocumentEmbeddingTextFormatter();
	}

	@Bean
	KnowledgeDocumentEmbedder acceptedAnswerKnowledgeDocumentEmbedder(
		KnowledgeDocumentEmbeddingTextFormatter formatter,
		GeminiEmbeddingGateway embeddingGateway
	) {
		return new KnowledgeDocumentEmbedder(formatter, embeddingGateway);
	}

	@Bean
	AcceptedAnswerKnowledgeIngestionService acceptedAnswerKnowledgeIngestionService(
		AcceptedAnswerKnowledgeRepository repository,
		KnowledgeDocumentEmbedder embedder,
		AcceptedAnswerKnowledgeDispatchProperties properties
	) {
		return new AcceptedAnswerKnowledgeIngestionService(
			repository,
			embedder,
			properties.taskLease(),
			properties.maxAttempts(),
			properties.retryDelay()
		);
	}
}
