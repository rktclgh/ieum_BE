package shinhan.fibri.ieum.ai.question.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.ai.question.analysis.GeoScope;
import shinhan.fibri.ieum.ai.question.analysis.ModelQuestionAnalysisInput;
import shinhan.fibri.ieum.ai.question.analysis.QueryAnalysis;
import shinhan.fibri.ieum.ai.question.analysis.QuestionInputSnapshot;
import shinhan.fibri.ieum.ai.question.analysis.QuestionQueryAnalyzer;
import shinhan.fibri.ieum.ai.question.analysis.QuestionSnapshotRepository;
import shinhan.fibri.ieum.ai.question.analysis.RegionContext;
import shinhan.fibri.ieum.ai.question.analysis.StoredAddressRegionParser;
import shinhan.fibri.ieum.ai.question.analysis.StoredLocationSnapshot;
import shinhan.fibri.ieum.ai.question.checkpoint.QuestionCheckpointResult;
import shinhan.fibri.ieum.ai.question.checkpoint.QuestionCheckpointService;
import shinhan.fibri.ieum.ai.question.checkpoint.QuestionTaskStage;
import shinhan.fibri.ieum.ai.question.citation.AnswerCitation;
import shinhan.fibri.ieum.ai.question.citation.QuestionAnswerCitationAssembler;
import shinhan.fibri.ieum.ai.question.embedding.QuestionEmbedding;
import shinhan.fibri.ieum.ai.question.embedding.QuestionEmbeddingGateway;
import shinhan.fibri.ieum.ai.question.embedding.QuestionEmbeddingTextFormatter;
import shinhan.fibri.ieum.ai.question.finalization.GroundedQuestionAnswerFinalization;
import shinhan.fibri.ieum.ai.question.finalization.InsufficientQuestionAnswerFinalization;
import shinhan.fibri.ieum.ai.question.finalization.QuestionAnswerFinalizationResult;
import shinhan.fibri.ieum.ai.question.finalization.QuestionAnswerFinalizationService;
import shinhan.fibri.ieum.ai.question.finalization.QuestionAnswerMode;
import shinhan.fibri.ieum.ai.question.generation.GeneratedAnswer;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerGateway;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerPrompt;
import shinhan.fibri.ieum.ai.question.grounding.GroundingValidation;
import shinhan.fibri.ieum.ai.question.grounding.GroundingValidationResult;
import shinhan.fibri.ieum.ai.question.grounding.LocalGroundingGateway;
import shinhan.fibri.ieum.ai.question.grounding.LocalGroundingRequest;
import shinhan.fibri.ieum.ai.question.repository.ClaimedQuestionTask;
import shinhan.fibri.ieum.ai.question.retrieval.GroundingSufficiencyPolicy;
import shinhan.fibri.ieum.ai.question.retrieval.GroundingSufficiencyResult;
import shinhan.fibri.ieum.ai.question.retrieval.VectorKnowledgeEvidence;
import shinhan.fibri.ieum.ai.question.retrieval.VectorKnowledgeRetrievalResult;
import shinhan.fibri.ieum.ai.question.retrieval.VectorOnlyKnowledgeRetrievalService;

class DefaultQuestionAnswerOrchestratorTest {

	@Test
	void completesTheLocalGroundedFlowWithOneEvidenceSnapshotAndCoarseModelRegion() {
		Fixture fixture = new Fixture();

		fixture.orchestrator.process(fixture.task);

		ArgumentCaptor<ModelQuestionAnalysisInput> analysisInput = ArgumentCaptor.forClass(
			ModelQuestionAnalysisInput.class
		);
		verify(fixture.analyzer).analyze(analysisInput.capture());
		assertThat(analysisInput.getValue().coarseRegion()).isEqualTo(
			RegionContext.korea("서울특별시", "중구", "태평로1가", null)
		);

		ArgumentCaptor<shinhan.fibri.ieum.ai.question.retrieval.VectorKnowledgeRetrievalRequest> retrieval =
			ArgumentCaptor.forClass(shinhan.fibri.ieum.ai.question.retrieval.VectorKnowledgeRetrievalRequest.class);
		verify(fixture.retrievalService).retrieve(retrieval.capture());
		assertThat(retrieval.getValue().geoScope())
			.isEqualTo(shinhan.fibri.ieum.ai.question.retrieval.GeoScope.local);
		assertThat(retrieval.getValue().coordinates().latitude()).isEqualTo(37.5665d);
		assertThat(retrieval.getValue().coordinates().longitude()).isEqualTo(126.978d);
		assertThat(retrieval.getValue().regionContext())
			.isEqualTo(new shinhan.fibri.ieum.ai.question.retrieval.RegionContext("서울특별시", "중구"));

		ArgumentCaptor<LocalAnswerPrompt> prompt = ArgumentCaptor.forClass(LocalAnswerPrompt.class);
		verify(fixture.answerGateway).generate(prompt.capture(), eq(Duration.ofSeconds(30)));
		assertThat(prompt.getValue().coarseRegion().country()).isEqualTo("KR");
		assertThat(prompt.getValue().coarseRegion().eupMyeonDong()).isEqualTo("태평로1가");
		assertThat(prompt.getValue().evidence()).extracting(item -> item.excerpt())
			.containsExactly(fixture.evidence.getFirst().excerpt());

		ArgumentCaptor<LocalGroundingRequest> grounding = ArgumentCaptor.forClass(LocalGroundingRequest.class);
		verify(fixture.groundingGateway).validate(grounding.capture(), eq(Duration.ofSeconds(30)));
		assertThat(grounding.getValue().prompt()).isSameAs(prompt.getValue());
		verify(fixture.retrievalService).revalidateEvidence(same(fixture.retrievalResult.evidence()));
		verify(fixture.sufficiencyPolicy).evaluate(same(fixture.revalidatedEvidence), eq(false));
		verify(fixture.citationAssembler).assemble(
			eq(fixture.generated.answer()),
			same(fixture.revalidatedEvidence),
			same(fixture.generated.citations())
		);

		ArgumentCaptor<GroundedQuestionAnswerFinalization> finalization = ArgumentCaptor.forClass(
			GroundedQuestionAnswerFinalization.class
		);
		verify(fixture.finalizationService).completeGrounded(finalization.capture());
		assertThat(finalization.getValue().answerMode()).isEqualTo(QuestionAnswerMode.LOCAL_GROUNDED);
		assertThat(finalization.getValue().context().generationProvider()).isEqualTo("bedrock");
		assertThat(finalization.getValue().context().groundingScore()).isEqualByComparingTo("0.91");
		assertThat(finalization.getValue().context().regionContext().fieldNames())
			.toIterable()
			.containsExactly("country", "sido", "sigungu", "eupMyeonDong", "place");
		assertThat(finalization.getValue().context().regionContext().get("place").isNull()).isTrue();
		verify(fixture.callbackWake).wake(fixture.task.questionId());

		InOrder providerOrder = inOrder(
			fixture.snapshotRepository,
			fixture.checkpointService,
			fixture.analyzer,
			fixture.embeddingGateway,
			fixture.retrievalService,
			fixture.answerGateway,
			fixture.groundingGateway,
			fixture.finalizationService,
			fixture.callbackWake
		);
		providerOrder.verify(fixture.snapshotRepository).findActiveByQuestionId(fixture.task.questionId());
		providerOrder.verify(fixture.checkpointService).guardCurrentStage(
			fixture.task, QuestionTaskStage.ANALYZING, Fixture.LEASE
		);
		providerOrder.verify(fixture.analyzer).analyze(any());
		providerOrder.verify(fixture.checkpointService).saveAnalysis(fixture.task, fixture.analysis, Fixture.LEASE);
		providerOrder.verify(fixture.checkpointService).guardCurrentStage(
			fixture.task, QuestionTaskStage.EMBEDDING, Fixture.LEASE
		);
		providerOrder.verify(fixture.embeddingGateway).embed(Fixture.EMBEDDING_TEXT);
		providerOrder.verify(fixture.checkpointService).saveEmbedding(
			fixture.task, fixture.embedding, Fixture.LEASE
		);
		providerOrder.verify(fixture.retrievalService).retrieve(any());
		providerOrder.verify(fixture.checkpointService).guardCurrentStage(
			fixture.task, QuestionTaskStage.RETRIEVING, Fixture.LEASE
		);
		providerOrder.verify(fixture.retrievalService).revalidateEvidence(any());
		providerOrder.verify(fixture.checkpointService).guardAndAdvance(
			fixture.task, QuestionTaskStage.RETRIEVING, QuestionTaskStage.GENERATING, Fixture.LEASE
		);
		providerOrder.verify(fixture.checkpointService).guardCurrentStage(
			fixture.task, QuestionTaskStage.GENERATING, Fixture.LEASE
		);
		providerOrder.verify(fixture.answerGateway).generate(any(), eq(Duration.ofSeconds(30)));
		providerOrder.verify(fixture.checkpointService).guardAndAdvance(
			fixture.task, QuestionTaskStage.GENERATING, QuestionTaskStage.VALIDATING, Fixture.LEASE
		);
		providerOrder.verify(fixture.checkpointService).guardCurrentStage(
			fixture.task, QuestionTaskStage.VALIDATING, Fixture.LEASE
		);
		providerOrder.verify(fixture.groundingGateway).validate(any(), eq(Duration.ofSeconds(30)));
		providerOrder.verify(fixture.checkpointService).guardAndAdvance(
			fixture.task, QuestionTaskStage.VALIDATING, QuestionTaskStage.PERSISTING, Fixture.LEASE
		);
		providerOrder.verify(fixture.checkpointService).guardCurrentStage(
			fixture.task, QuestionTaskStage.PERSISTING, Fixture.LEASE
		);
		providerOrder.verify(fixture.finalizationService).completeGrounded(any());
		providerOrder.verify(fixture.callbackWake).wake(fixture.task.questionId());
	}

	@Test
	void repairsExactlyOnceAndPersistsTheRepairProvenance() {
		Fixture fixture = new Fixture();
		GroundingValidationResult unsupported = fixture.validation(false, "0.31");
		GroundingValidationResult supported = fixture.validation(true, "0.88");
		GeneratedAnswer repaired = fixture.answer("수정 답변", "gemini", "gemini-3.1-flash-lite", "repair-v1");
		when(fixture.groundingGateway.validate(any(), any())).thenReturn(unsupported, supported);
		when(fixture.groundingGateway.repair(any(), same(unsupported.validation()), any())).thenReturn(repaired);
		when(fixture.citationAssembler.assemble(
			eq(repaired.answer()),
			same(fixture.revalidatedEvidence),
			same(repaired.citations())
		)).thenReturn(List.of(fixture.citationEvidence()));

		fixture.orchestrator.process(fixture.task);

		ArgumentCaptor<LocalGroundingRequest> requests = ArgumentCaptor.forClass(LocalGroundingRequest.class);
		verify(fixture.groundingGateway, org.mockito.Mockito.times(2)).validate(
			requests.capture(), eq(Duration.ofSeconds(30))
		);
		assertThat(requests.getAllValues().get(0).prompt())
			.isSameAs(requests.getAllValues().get(1).prompt());
		verify(fixture.groundingGateway).repair(
			same(requests.getAllValues().get(0)),
			same(unsupported.validation()),
			eq(Duration.ofSeconds(30))
		);
		ArgumentCaptor<GroundedQuestionAnswerFinalization> command = ArgumentCaptor.forClass(
			GroundedQuestionAnswerFinalization.class
		);
		verify(fixture.finalizationService).completeGrounded(command.capture());
		assertThat(command.getValue().content()).isEqualTo("수정 답변");
		assertThat(command.getValue().context().generationProvider()).isEqualTo("gemini");
		assertThat(command.getValue().context().generationModel()).isEqualTo("gemini-3.1-flash-lite");
		assertThat(command.getValue().context().promptVersion()).isEqualTo("repair-v1");
		assertThat(command.getValue().context().groundingScore()).isEqualByComparingTo("0.88");
	}

	@Test
	void completesInsufficientWithoutGenerationWhenLocalEvidenceIsInsufficient() {
		Fixture fixture = new Fixture();
		when(fixture.sufficiencyPolicy.evaluate(any(), any(Boolean.class)))
			.thenReturn(new GroundingSufficiencyResult(
				GroundingSufficiencyResult.Decision.INSUFFICIENT,
				GroundingSufficiencyResult.Reason.EMPTY_EVIDENCE
			));

		fixture.orchestrator.process(fixture.task);

		verifyNoInteractions(fixture.answerGateway, fixture.groundingGateway, fixture.citationAssembler);
		ArgumentCaptor<InsufficientQuestionAnswerFinalization> command = ArgumentCaptor.forClass(
			InsufficientQuestionAnswerFinalization.class
		);
		verify(fixture.finalizationService).completeInsufficient(command.capture());
		assertThat(command.getValue().context().generationProvider()).isNull();
		assertThat(command.getValue().context().generationModel()).isNull();
		assertThat(command.getValue().context().promptVersion()).isNull();
		assertThat(command.getValue().context().groundingScore()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(command.getValue().context().evidence()).isEmpty();
		verify(fixture.checkpointService).guardAndAdvance(
			fixture.task,
			QuestionTaskStage.RETRIEVING,
			QuestionTaskStage.PERSISTING,
			Fixture.LEASE
		);
		verify(fixture.checkpointService, never()).guardAndAdvance(
			fixture.task,
			QuestionTaskStage.RETRIEVING,
			QuestionTaskStage.WEB_GROUNDING,
			Fixture.LEASE
		);
		verify(fixture.callbackWake, never()).wake(anyLong());
	}

	@Test
	void completesInsufficientWithRepairProvenanceWhenSecondValidationIsUnsupported() {
		Fixture fixture = new Fixture();
		GroundingValidationResult first = fixture.validation(false, "0.25");
		GroundingValidationResult second = fixture.validation(false, "0.44");
		GeneratedAnswer repaired = fixture.answer("여전히 불충분", "gemini", "gemini-3.1-flash-lite", "repair-v1");
		when(fixture.groundingGateway.validate(any(), any())).thenReturn(first, second);
		when(fixture.groundingGateway.repair(any(), same(first.validation()), any())).thenReturn(repaired);

		fixture.orchestrator.process(fixture.task);

		verify(fixture.groundingGateway, org.mockito.Mockito.times(2)).validate(any(), any());
		verify(fixture.groundingGateway, org.mockito.Mockito.times(1)).repair(any(), any(), any());
		ArgumentCaptor<InsufficientQuestionAnswerFinalization> command = ArgumentCaptor.forClass(
			InsufficientQuestionAnswerFinalization.class
		);
		verify(fixture.finalizationService).completeInsufficient(command.capture());
		assertThat(command.getValue().context().generationProvider()).isEqualTo("gemini");
		assertThat(command.getValue().context().promptVersion()).isEqualTo("repair-v1");
		assertThat(command.getValue().context().groundingScore()).isEqualByComparingTo("0.44");
		assertThat(command.getValue().context().evidence()).isEmpty();
		verify(fixture.checkpointService).guardAndAdvance(
			fixture.task,
			QuestionTaskStage.VALIDATING,
			QuestionTaskStage.PERSISTING,
			Fixture.LEASE
		);
		verify(fixture.checkpointService, never()).guardAndAdvance(
			fixture.task,
			QuestionTaskStage.VALIDATING,
			QuestionTaskStage.WEB_GROUNDING,
			Fixture.LEASE
		);
		verify(fixture.callbackWake, never()).wake(anyLong());
	}

	@Test
	void stopsBeforeEmbeddingProviderWhenTheEmbeddingGuardCancels() {
		Fixture fixture = new Fixture();
		when(fixture.checkpointService.guardCurrentStage(
			fixture.task, QuestionTaskStage.EMBEDDING, Fixture.LEASE
		)).thenReturn(QuestionCheckpointResult.CANCELLED);

		fixture.orchestrator.process(fixture.task);

		verify(fixture.embeddingGateway, never()).embed(any());
		verifyNoInteractions(fixture.retrievalService, fixture.answerGateway, fixture.finalizationService);
	}

	@Test
	void stopsBeforeFinalizationWhenThePersistingGuardCancels() {
		Fixture fixture = new Fixture();
		when(fixture.checkpointService.guardCurrentStage(
			fixture.task, QuestionTaskStage.PERSISTING, Fixture.LEASE
		)).thenReturn(QuestionCheckpointResult.CANCELLED);

		fixture.orchestrator.process(fixture.task);

		verifyNoInteractions(fixture.finalizationService, fixture.callbackWake);
	}

	@Test
	void swallowsCallbackWakeFailureAfterACommittedAnswer() {
		Fixture fixture = new Fixture();
		org.mockito.Mockito.doThrow(new IllegalStateException("secret callback detail"))
			.when(fixture.callbackWake).wake(fixture.task.questionId());

		assertThatCode(() -> fixture.orchestrator.process(fixture.task)).doesNotThrowAnyException();
		verify(fixture.callbackWake).wake(fixture.task.questionId());
	}

	@Test
	void doesNotWakeCallbackWhenFinalizerReturnsWithoutAnAnswer() {
		Fixture fixture = new Fixture();
		when(fixture.finalizationService.completeGrounded(any()))
			.thenReturn(new QuestionAnswerFinalizationResult(fixture.task.questionId(), null));

		fixture.orchestrator.process(fixture.task);

		verifyNoInteractions(fixture.callbackWake);
	}

	@Test
	void turnsAnAbsentActiveSnapshotIntoPermanentInputOnlyAfterTheInitialGuard() {
		Fixture fixture = new Fixture();
		when(fixture.snapshotRepository.findActiveByQuestionId(fixture.task.questionId()))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> fixture.orchestrator.process(fixture.task))
			.isInstanceOfSatisfying(QuestionTaskFailureException.class, exception ->
				assertThat(exception.failure()).isEqualTo(QuestionTaskFailure.PERMANENT_INPUT)
			);
		InOrder order = inOrder(fixture.snapshotRepository, fixture.checkpointService);
		order.verify(fixture.snapshotRepository).findActiveByQuestionId(fixture.task.questionId());
		order.verify(fixture.checkpointService).guardCurrentStage(
			fixture.task, QuestionTaskStage.ANALYZING, Fixture.LEASE
		);
		verifyNoInteractions(fixture.analyzer);
	}

	@Test
	void processMustNeverJoinOrCreateATransaction() throws Exception {
		Transactional transactional = DefaultQuestionAnswerOrchestrator.class
			.getMethod("process", ClaimedQuestionTask.class)
			.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.propagation()).isEqualTo(Propagation.NEVER);
	}

	private static final class Fixture {

		private static final Duration LEASE = Duration.ofMinutes(2);
		private static final String EMBEDDING_TEXT = "title: 버스는 어떻게 타나요? | text: 승하차 방법을 알려주세요.";

		private final QuestionSnapshotRepository snapshotRepository = mock(QuestionSnapshotRepository.class);
		private final QuestionQueryAnalyzer analyzer = mock(QuestionQueryAnalyzer.class);
		private final QuestionCheckpointService checkpointService = mock(QuestionCheckpointService.class);
		private final QuestionEmbeddingTextFormatter embeddingFormatter = mock(QuestionEmbeddingTextFormatter.class);
		private final QuestionEmbeddingGateway embeddingGateway = mock(QuestionEmbeddingGateway.class);
		private final VectorOnlyKnowledgeRetrievalService retrievalService = mock(
			VectorOnlyKnowledgeRetrievalService.class
		);
		private final GroundingSufficiencyPolicy sufficiencyPolicy = mock(GroundingSufficiencyPolicy.class);
		private final LocalAnswerGateway answerGateway = mock(LocalAnswerGateway.class);
		private final LocalGroundingGateway groundingGateway = mock(LocalGroundingGateway.class);
		private final QuestionAnswerCitationAssembler citationAssembler = mock(
			QuestionAnswerCitationAssembler.class
		);
		private final QuestionAnswerFinalizationService finalizationService = mock(
			QuestionAnswerFinalizationService.class
		);
		private final QuestionCompletionCallbackWake callbackWake = mock(QuestionCompletionCallbackWake.class);
		private final ObjectMapper objectMapper = new ObjectMapper();
		private final ClaimedQuestionTask task = new ClaimedQuestionTask(
			41L,
			"worker-1",
			UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
			OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2),
			1
		);
		private final QuestionInputSnapshot snapshot = new QuestionInputSnapshot(
			"버스는 어떻게 타나요?",
			"승하차 방법을 알려주세요.",
			new StoredLocationSnapshot(
				37.5665d,
				126.978d,
				"대한민국 서울특별시 중구 태평로1가 31",
				"사용자 상세주소",
				"사용자 레이블"
			)
		);
		private final QueryAnalysis analysis = new QueryAnalysis(
			GeoScope.local,
			new BigDecimal("0.84"),
			RegionContext.korea("서울특별시", "중구", "태평로1가", null),
			"transport",
			false,
			List.of("버스"),
			List.of("버스 승하차"),
			"query-analysis-v1"
		);
		private final QuestionEmbedding embedding = new QuestionEmbedding(
			"gemini-embedding-2",
			java.util.Collections.nCopies(768, 0.01f)
		);
		private final List<VectorKnowledgeEvidence> evidence = List.of(evidence());
		private final VectorKnowledgeRetrievalResult retrievalResult = new VectorKnowledgeRetrievalResult(
			"vector-only-v1",
			List.of(evidence.getFirst()),
			List.of(evidence.getFirst())
		);
		private final List<VectorKnowledgeEvidence> revalidatedEvidence = List.copyOf(evidence);
		private final GeneratedAnswer generated = answer("앞문으로 타고 뒷문으로 내립니다.", "bedrock", "nova-micro", "local-answer-v1");
		private final GroundingValidationResult supported = validation(true, "0.91");
		private final DefaultQuestionAnswerOrchestrator orchestrator;

		private Fixture() {
			when(snapshotRepository.findActiveByQuestionId(task.questionId())).thenReturn(Optional.of(snapshot));
			when(checkpointService.guardCurrentStage(any(), any(), any()))
				.thenReturn(QuestionCheckpointResult.APPLIED);
			when(checkpointService.guardAndAdvance(any(), any(), any(), any()))
				.thenReturn(QuestionCheckpointResult.APPLIED);
			when(checkpointService.saveAnalysis(any(), any(), any()))
				.thenReturn(QuestionCheckpointResult.APPLIED);
			when(checkpointService.saveEmbedding(any(), any(), any()))
				.thenReturn(QuestionCheckpointResult.APPLIED);
			when(analyzer.analyze(any())).thenReturn(analysis);
			when(embeddingFormatter.format(snapshot)).thenReturn(EMBEDDING_TEXT);
			when(embeddingGateway.embed(EMBEDDING_TEXT)).thenReturn(embedding);
			when(retrievalService.retrieve(any())).thenReturn(retrievalResult);
			when(retrievalService.revalidateEvidence(same(retrievalResult.evidence())))
				.thenReturn(revalidatedEvidence);
			when(sufficiencyPolicy.evaluate(same(revalidatedEvidence), eq(false)))
				.thenReturn(new GroundingSufficiencyResult(
					GroundingSufficiencyResult.Decision.SUFFICIENT,
					GroundingSufficiencyResult.Reason.NON_EMPTY_LOW_RISK_EVIDENCE
				));
			when(answerGateway.generate(any(), eq(Duration.ofSeconds(30)))).thenReturn(generated);
			when(groundingGateway.validate(any(), eq(Duration.ofSeconds(30)))).thenReturn(supported);
			when(citationAssembler.assemble(
				eq(generated.answer()),
				same(revalidatedEvidence),
				same(generated.citations())
			)).thenReturn(List.of(citationEvidence()));
			when(finalizationService.completeGrounded(any()))
				.thenReturn(new QuestionAnswerFinalizationResult(task.questionId(), 900L));
			when(finalizationService.completeInsufficient(any()))
				.thenReturn(new QuestionAnswerFinalizationResult(task.questionId(), null));
			orchestrator = new DefaultQuestionAnswerOrchestrator(
				snapshotRepository,
				new StoredAddressRegionParser(),
				analyzer,
				checkpointService,
				embeddingFormatter,
				embeddingGateway,
				retrievalService,
				sufficiencyPolicy,
				answerGateway,
				groundingGateway,
				citationAssembler,
				finalizationService,
				callbackWake,
				objectMapper,
				LEASE,
				Duration.ofSeconds(30),
				Duration.ofSeconds(30)
			);
		}

		private GroundingValidationResult validation(boolean supported, String score) {
			return new GroundingValidationResult(
				new GroundingValidation(
					supported,
					new BigDecimal(score),
					supported ? List.of() : List.of("근거가 부족한 주장")
				),
				"bedrock",
				"nova-micro",
				"grounding-v1",
				Instant.parse("2026-07-13T01:00:00Z"),
				20,
				5,
				"request-grounding",
				null
			);
		}

		private GeneratedAnswer answer(String content, String provider, String model, String promptVersion) {
			return new GeneratedAnswer(
				content,
				List.of(new AnswerCitation(0, 0, Math.min(3, content.length()))),
				provider,
				model,
				promptVersion,
				Instant.parse("2026-07-13T00:00:00Z"),
				100,
				20,
				"request-answer",
				null
			);
		}

		private VectorKnowledgeEvidence evidence() {
			return new VectorKnowledgeEvidence(
				1L,
				11L,
				"curated",
				"버스 승하차 안내",
				"앞문으로 승차하고 뒷문으로 하차합니다.",
				"community",
				"a".repeat(64),
				"https://example.com/bus",
				null,
				"transport",
				shinhan.fibri.ieum.ai.question.retrieval.GeoScope.general,
				new BigDecimal("0.90"),
				new BigDecimal("0.95"),
				new BigDecimal("0.80"),
				new BigDecimal("0.92"),
				null,
				Instant.parse("2026-07-13T00:00:00Z")
			);
		}

		private JsonNode citationEvidence() {
			return objectMapper.createObjectNode()
				.put("type", "knowledge_chunk")
				.put("sourceId", 1L)
				.put("chunkId", 11L)
				.put("sourceType", "curated")
				.put("title", "버스 승하차 안내")
				.put("excerpt", "앞문으로 승차하고 뒷문으로 하차합니다.")
				.put("url", "https://example.com/bus")
				.put("domain", "transport")
				.put("contentHash", "a".repeat(64))
				.put("score", 0.92d)
				.put("startIndex", 0)
				.put("endIndex", 3)
				.put("retrievedAt", "2026-07-13T00:00:00Z");
		}
	}
}
