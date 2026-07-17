package shinhan.fibri.ieum.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import shinhan.fibri.ieum.ai.knowledge.relations.BedrockKnowledgeRelationCandidateExtractor;
import shinhan.fibri.ieum.ai.knowledge.relations.JdbcKnowledgeRelationCandidateRepository;
import shinhan.fibri.ieum.ai.knowledge.relations.KnowledgeRelationCandidateExtractionService;
import shinhan.fibri.ieum.ai.knowledge.relations.KnowledgeRelationCandidateExtractor;
import shinhan.fibri.ieum.ai.knowledge.relations.KnowledgeRelationCandidateRepository;
import shinhan.fibri.ieum.ai.knowledge.relations.KnowledgeRelationCandidateTaskLane;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
	prefix = "app.ai.features",
	name = "knowledge-relation-extraction-enabled",
	havingValue = "true"
)
public class KnowledgeRelationCandidateConfiguration {

	private static final int QUEUE_CAPACITY = 8;

	@Bean("knowledgeRelationCandidateTaskExecutor")
	public ThreadPoolTaskExecutor knowledgeRelationCandidateTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("ieum-kg-candidate-");
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
	KnowledgeRelationCandidateTaskLane knowledgeRelationCandidateTaskLane(
		@Qualifier("knowledgeRelationCandidateTaskExecutor") ThreadPoolTaskExecutor executor,
		ObjectProvider<KnowledgeRelationCandidateExtractionService> serviceProvider
	) {
		KnowledgeRelationCandidateExtractionService service = serviceProvider.getIfAvailable();
		if (service == null) {
			throw new IllegalStateException("Knowledge relation extraction requires its processor");
		}
		return new KnowledgeRelationCandidateTaskLane(true, executor, service);
	}
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
	prefix = "app.ai.features",
	name = "knowledge-relation-extraction-enabled",
	havingValue = "true"
)
class KnowledgeRelationCandidateEnabledConfiguration {

	@Bean
	KnowledgeRelationCandidateRepository knowledgeRelationCandidateRepository(
		JdbcClient jdbcClient,
		PlatformTransactionManager transactionManager
	) {
		return new JdbcKnowledgeRelationCandidateRepository(jdbcClient, transactionManager);
	}

	@Bean
	KnowledgeRelationCandidateExtractor knowledgeRelationCandidateExtractor(
		ChatModel chatModel,
		ObjectMapper objectMapper,
		@Value("${app.ai.knowledge-relation.model:amazon.nova-lite-v1:0}") String model,
		@Value("${app.ai.knowledge-relation.max-tokens:1024}") int maxTokens
	) {
		return new BedrockKnowledgeRelationCandidateExtractor(chatModel, objectMapper, model, maxTokens);
	}

	@Bean
	KnowledgeRelationCandidateExtractionService knowledgeRelationCandidateExtractionService(
		KnowledgeRelationCandidateRepository repository,
		KnowledgeRelationCandidateExtractor extractor,
		@Value("${app.ai.knowledge-relation.task-lease:5m}") String taskLease,
		@Value("${app.ai.knowledge-relation.retry-delay:2m}") String retryDelay,
		@Value("${app.ai.knowledge-relation.max-attempts:5}") int maxAttempts
	) {
		return new KnowledgeRelationCandidateExtractionService(
			repository,
			extractor,
			DurationStyle.detectAndParse(taskLease),
			DurationStyle.detectAndParse(retryDelay),
			maxAttempts
		);
	}
}
