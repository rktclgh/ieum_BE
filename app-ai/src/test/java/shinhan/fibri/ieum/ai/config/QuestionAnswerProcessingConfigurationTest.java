package shinhan.fibri.ieum.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
import shinhan.fibri.ieum.ai.question.retrieval.VectorOnlyKnowledgeRetrievalService;
import shinhan.fibri.ieum.ai.question.service.DefaultQuestionAnswerOrchestrator;
import shinhan.fibri.ieum.ai.question.service.QuestionAnswerOrchestrator;
import shinhan.fibri.ieum.ai.question.service.QuestionCompletionCallbackWake;

class QuestionAnswerProcessingConfigurationTest {

	private static final Duration TASK_LEASE = Duration.ofSeconds(37);
	private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(30);

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(QuestionAnswerProcessingConfiguration.class);

	@Test
	void answerOffCreatesNoProcessingBeansAndRequiresNoDependenciesOrSecrets() {
		contextRunner
			.withPropertyValues("app.ai.features.question-answer-enabled=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(QuestionAnswerOrchestrator.class);
				assertThat(context).doesNotHaveBean(StoredAddressRegionParser.class);
				assertThat(context).doesNotHaveBean(QuestionEmbeddingTextFormatter.class);
				assertThat(context).doesNotHaveBean(GroundingSufficiencyPolicy.class);
				assertThat(context).doesNotHaveBean(QuestionAnswerCitationAssembler.class);
				assertThat(context).doesNotHaveBean(LocalAnswerProperties.class);
				assertThat(context).doesNotHaveBean(LocalGroundingProperties.class);
			});
	}

	@Test
	void webGroundingOnOmitsTheLocalOnlyOrchestratorWithoutRequiringDependencies() {
		contextRunner
			.withPropertyValues(
				"app.ai.features.question-answer-enabled=true",
				"app.ai.features.web-grounding-enabled=true"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(QuestionAnswerOrchestrator.class);
				assertThat(context).doesNotHaveBean(StoredAddressRegionParser.class);
				assertThat(context).doesNotHaveBean(QuestionEmbeddingTextFormatter.class);
				assertThat(context).doesNotHaveBean(GroundingSufficiencyPolicy.class);
				assertThat(context).doesNotHaveBean(QuestionAnswerCitationAssembler.class);
			});
	}

	@Test
	void localModeWiresExactlyOneOrchestratorWhenWebGroundingIsFalseOrMissing() {
		assertLocalMode(enabledContext());
		assertLocalMode(enabledContext()
			.withPropertyValues("app.ai.features.web-grounding-enabled=false"));
	}

	private void assertLocalMode(ApplicationContextRunner runner) {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(QuestionAnswerOrchestrator.class);
			assertThat(context).hasSingleBean(DefaultQuestionAnswerOrchestrator.class);
			assertThat(context).hasSingleBean(StoredAddressRegionParser.class);
			assertThat(context).hasSingleBean(QuestionEmbeddingTextFormatter.class);
			assertThat(context).hasSingleBean(GroundingSufficiencyPolicy.class);
			assertThat(context).hasSingleBean(QuestionAnswerCitationAssembler.class);

			DirectFieldAccessor fields = new DirectFieldAccessor(
				context.getBean(DefaultQuestionAnswerOrchestrator.class)
			);
			assertThat(fields.getPropertyValue("leaseExtension")).isEqualTo(TASK_LEASE);
			assertThat(fields.getPropertyValue("answerTimeout")).isEqualTo(MODEL_TIMEOUT);
			assertThat(fields.getPropertyValue("groundingTimeout")).isEqualTo(MODEL_TIMEOUT);
		});
	}

	private ApplicationContextRunner enabledContext() {
		return contextRunner
			.withPropertyValues("app.ai.features.question-answer-enabled=true")
			.withBean(ObjectMapper.class, ObjectMapper::new)
			.withBean(QuestionAnswerDispatchProperties.class, this::dispatchProperties)
			.withBean(LocalAnswerProperties.class, this::answerProperties)
			.withBean(LocalGroundingProperties.class, this::groundingProperties)
			.withBean(QuestionSnapshotRepository.class, () -> mock(QuestionSnapshotRepository.class))
			.withBean(QuestionQueryAnalyzer.class, () -> mock(QuestionQueryAnalyzer.class))
			.withBean(QuestionCheckpointService.class, () -> mock(QuestionCheckpointService.class))
			.withBean(QuestionEmbeddingGateway.class, () -> mock(QuestionEmbeddingGateway.class))
			.withBean(
				VectorOnlyKnowledgeRetrievalService.class,
				() -> mock(VectorOnlyKnowledgeRetrievalService.class)
			)
			.withBean(LocalAnswerGateway.class, () -> mock(LocalAnswerGateway.class))
			.withBean(LocalGroundingGateway.class, () -> mock(LocalGroundingGateway.class))
			.withBean(
				QuestionAnswerFinalizationService.class,
				() -> mock(QuestionAnswerFinalizationService.class)
			)
			.withBean(
				QuestionCompletionCallbackWake.class,
				() -> mock(QuestionCompletionCallbackWake.class)
			);
	}

	private QuestionAnswerDispatchProperties dispatchProperties() {
		return new QuestionAnswerDispatchProperties(
			true,
			TASK_LEASE,
			5,
			Duration.ofSeconds(60),
			32,
			5
		);
	}

	private LocalAnswerProperties answerProperties() {
		return new LocalAnswerProperties(
			"amazon.nova-micro-v1:0",
			"gemini-3.1-flash-lite",
			"test-key",
			"question-local-answer-v1",
			1024,
			MODEL_TIMEOUT
		);
	}

	private LocalGroundingProperties groundingProperties() {
		return new LocalGroundingProperties(
			"question-grounding-validation-v1",
			"question-grounding-repair-v1",
			512,
			1024,
			MODEL_TIMEOUT
		);
	}
}
