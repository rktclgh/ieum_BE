package shinhan.fibri.ieum.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import shinhan.fibri.ieum.ai.question.analysis.QuestionQueryAnalyzer;
import shinhan.fibri.ieum.ai.question.analysis.QuestionSnapshotRepository;
import shinhan.fibri.ieum.ai.question.analysis.StoredAddressRegionParser;
import shinhan.fibri.ieum.ai.question.checkpoint.QuestionCheckpointService;
import shinhan.fibri.ieum.ai.question.citation.QuestionAnswerCitationAssembler;
import shinhan.fibri.ieum.ai.question.embedding.QuestionEmbeddingGateway;
import shinhan.fibri.ieum.ai.question.embedding.QuestionEmbeddingTextFormatter;
import shinhan.fibri.ieum.ai.question.finalization.QuestionAnswerFinalizationService;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerGateway;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerProperties;
import shinhan.fibri.ieum.ai.question.grounding.LocalGroundingGateway;
import shinhan.fibri.ieum.ai.question.grounding.LocalGroundingProperties;
import shinhan.fibri.ieum.ai.question.retrieval.GroundingSufficiencyPolicy;
import shinhan.fibri.ieum.ai.question.retrieval.HybridKnowledgeRetrievalService;
import shinhan.fibri.ieum.ai.question.service.DefaultQuestionAnswerOrchestrator;
import shinhan.fibri.ieum.ai.question.service.QuestionCompletionCallbackWake;
import shinhan.fibri.ieum.ai.question.webgrounding.WebGroundingGateway;
import shinhan.fibri.ieum.ai.question.webgrounding.WebGroundingPromptFactory;
import shinhan.fibri.ieum.ai.question.webgrounding.WebQuestionEvidenceAssembler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
	prefix = "app.ai.features",
	name = "question-answer-enabled",
	havingValue = "true"
)
public class QuestionAnswerProcessingConfiguration {

	@Bean
	StoredAddressRegionParser storedAddressRegionParser() {
		return new StoredAddressRegionParser();
	}

	@Bean
	QuestionEmbeddingTextFormatter questionEmbeddingTextFormatter() {
		return new QuestionEmbeddingTextFormatter();
	}

	@Bean
	GroundingSufficiencyPolicy groundingSufficiencyPolicy() {
		return new GroundingSufficiencyPolicy();
	}

	@Bean
	QuestionAnswerCitationAssembler questionAnswerCitationAssembler(ObjectMapper objectMapper) {
		return new QuestionAnswerCitationAssembler(objectMapper);
	}

	@Bean
	WebGroundingPromptFactory webGroundingPromptFactory() {
		return new WebGroundingPromptFactory();
	}

	@Bean
	WebQuestionEvidenceAssembler webQuestionEvidenceAssembler(ObjectMapper objectMapper) {
		return new WebQuestionEvidenceAssembler(objectMapper, Clock.systemUTC());
	}

	@Bean
	DefaultQuestionAnswerOrchestrator questionAnswerOrchestrator(
		QuestionSnapshotRepository snapshotRepository,
		StoredAddressRegionParser regionParser,
		QuestionQueryAnalyzer analyzer,
		QuestionCheckpointService checkpointService,
		QuestionEmbeddingTextFormatter embeddingFormatter,
		QuestionEmbeddingGateway embeddingGateway,
		HybridKnowledgeRetrievalService retrievalService,
		GroundingSufficiencyPolicy sufficiencyPolicy,
		LocalAnswerGateway answerGateway,
		LocalGroundingGateway groundingGateway,
		WebGroundingGateway webGroundingGateway,
		WebGroundingPromptFactory webGroundingPromptFactory,
		WebQuestionEvidenceAssembler webEvidenceAssembler,
		QuestionAnswerCitationAssembler citationAssembler,
		QuestionAnswerFinalizationService finalizationService,
		QuestionCompletionCallbackWake callbackWake,
		ObjectMapper objectMapper,
		QuestionAnswerDispatchProperties dispatchProperties,
		LocalAnswerProperties answerProperties,
		LocalGroundingProperties groundingProperties
	) {
		return new DefaultQuestionAnswerOrchestrator(
			snapshotRepository,
			regionParser,
			analyzer,
			checkpointService,
			embeddingFormatter,
			embeddingGateway,
			retrievalService,
			sufficiencyPolicy,
			answerGateway,
			groundingGateway,
			webGroundingGateway,
			webGroundingPromptFactory,
			webEvidenceAssembler,
			citationAssembler,
			finalizationService,
			callbackWake,
			objectMapper,
			dispatchProperties.taskLease(),
			answerProperties.modelTimeout(),
			groundingProperties.modelTimeout()
		);
	}
}
