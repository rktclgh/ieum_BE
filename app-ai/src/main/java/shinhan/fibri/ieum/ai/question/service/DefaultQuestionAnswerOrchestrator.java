package shinhan.fibri.ieum.ai.question.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.ai.question.analysis.ModelQuestionAnalysisInput;
import shinhan.fibri.ieum.ai.question.analysis.QueryAnalysis;
import shinhan.fibri.ieum.ai.question.analysis.QuestionInputSnapshot;
import shinhan.fibri.ieum.ai.question.analysis.QuestionQueryAnalyzer;
import shinhan.fibri.ieum.ai.question.analysis.QuestionSnapshotRepository;
import shinhan.fibri.ieum.ai.question.analysis.RegionContext;
import shinhan.fibri.ieum.ai.question.analysis.StoredAddressRegionParser;
import shinhan.fibri.ieum.ai.question.checkpoint.QuestionCheckpointResult;
import shinhan.fibri.ieum.ai.question.checkpoint.QuestionCheckpointService;
import shinhan.fibri.ieum.ai.question.checkpoint.QuestionTaskStage;
import shinhan.fibri.ieum.ai.question.citation.QuestionAnswerCitationAssembler;
import shinhan.fibri.ieum.ai.question.embedding.QuestionEmbedding;
import shinhan.fibri.ieum.ai.question.embedding.QuestionEmbeddingGateway;
import shinhan.fibri.ieum.ai.question.embedding.QuestionEmbeddingTextFormatter;
import shinhan.fibri.ieum.ai.question.finalization.GroundedQuestionAnswerFinalization;
import shinhan.fibri.ieum.ai.question.finalization.InsufficientQuestionAnswerFinalization;
import shinhan.fibri.ieum.ai.question.finalization.QuestionAnswerFinalizationContext;
import shinhan.fibri.ieum.ai.question.finalization.QuestionAnswerFinalizationResult;
import shinhan.fibri.ieum.ai.question.finalization.QuestionAnswerFinalizationService;
import shinhan.fibri.ieum.ai.question.finalization.QuestionAnswerMode;
import shinhan.fibri.ieum.ai.question.finalization.QuestionTaskFence;
import shinhan.fibri.ieum.ai.question.finalization.UngroundedQuestionAnswerFinalization;
import shinhan.fibri.ieum.ai.question.generation.GeneratedAnswer;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerEvidence;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerGateway;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerPrompt;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerRegion;
import shinhan.fibri.ieum.ai.question.generation.UngroundedAnswer;
import shinhan.fibri.ieum.ai.question.generation.UngroundedAnswerGateway;
import shinhan.fibri.ieum.ai.question.grounding.GroundingValidationResult;
import shinhan.fibri.ieum.ai.question.grounding.LocalGroundingGateway;
import shinhan.fibri.ieum.ai.question.grounding.LocalGroundingRequest;
import shinhan.fibri.ieum.ai.question.repository.ClaimedQuestionTask;
import shinhan.fibri.ieum.ai.question.retrieval.GeoPoint;
import shinhan.fibri.ieum.ai.question.retrieval.GroundingSufficiencyPolicy;
import shinhan.fibri.ieum.ai.question.retrieval.GroundingSufficiencyResult;
import shinhan.fibri.ieum.ai.question.retrieval.HybridKnowledgeRetrievalResult;
import shinhan.fibri.ieum.ai.question.retrieval.HybridKnowledgeRetrievalService;
import shinhan.fibri.ieum.ai.question.retrieval.KnowledgeEvidence;
import shinhan.fibri.ieum.ai.question.retrieval.VectorKnowledgeRetrievalRequest;
import shinhan.fibri.ieum.ai.question.webgrounding.WebGroundedAnswer;
import shinhan.fibri.ieum.ai.question.webgrounding.WebGroundingGateway;
import shinhan.fibri.ieum.ai.question.webgrounding.WebGroundingPrompt;
import shinhan.fibri.ieum.ai.question.webgrounding.WebGroundingPromptFactory;
import shinhan.fibri.ieum.ai.question.webgrounding.WebQuestionEvidenceAssembler;
import shinhan.fibri.ieum.ai.question.webgrounding.QuestionWebGroundingUnavailableException;

public class DefaultQuestionAnswerOrchestrator implements QuestionAnswerOrchestrator {

	private static final Logger log = LoggerFactory.getLogger(DefaultQuestionAnswerOrchestrator.class);
	private static final Duration REQUIRED_MODEL_TIMEOUT = Duration.ofSeconds(30);
	private static final Duration WEB_GROUNDING_TIMEOUT = Duration.ofSeconds(45);
	private static final Duration MINIMUM_LEASE_EXTENSION = Duration.ofSeconds(65);

	private final QuestionSnapshotRepository snapshotRepository;
	private final StoredAddressRegionParser regionParser;
	private final QuestionQueryAnalyzer analyzer;
	private final QuestionCheckpointService checkpointService;
	private final QuestionEmbeddingTextFormatter embeddingFormatter;
	private final QuestionEmbeddingGateway embeddingGateway;
	private final HybridKnowledgeRetrievalService retrievalService;
	private final GroundingSufficiencyPolicy sufficiencyPolicy;
	private final LocalAnswerGateway answerGateway;
	private final LocalGroundingGateway groundingGateway;
	private final WebGroundingGateway webGroundingGateway;
	private final WebGroundingPromptFactory webGroundingPromptFactory;
	private final WebQuestionEvidenceAssembler webEvidenceAssembler;
	private final UngroundedAnswerGateway ungroundedAnswerGateway;
	private final QuestionAnswerCitationAssembler citationAssembler;
	private final QuestionAnswerFinalizationService finalizationService;
	private final QuestionCompletionCallbackWake callbackWake;
	private final ObjectMapper objectMapper;
	private final Duration leaseExtension;
	private final Duration answerTimeout;
	private final Duration groundingTimeout;
	private final boolean ungroundedAnswerEnabled;

	public DefaultQuestionAnswerOrchestrator(
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
		UngroundedAnswerGateway ungroundedAnswerGateway,
		QuestionAnswerCitationAssembler citationAssembler,
		QuestionAnswerFinalizationService finalizationService,
		QuestionCompletionCallbackWake callbackWake,
		ObjectMapper objectMapper,
		Duration leaseExtension,
		Duration answerTimeout,
		Duration groundingTimeout,
		boolean ungroundedAnswerEnabled
	) {
		this.snapshotRepository = Objects.requireNonNull(snapshotRepository, "snapshotRepository must not be null");
		this.regionParser = Objects.requireNonNull(regionParser, "regionParser must not be null");
		this.analyzer = Objects.requireNonNull(analyzer, "analyzer must not be null");
		this.checkpointService = Objects.requireNonNull(checkpointService, "checkpointService must not be null");
		this.embeddingFormatter = Objects.requireNonNull(embeddingFormatter, "embeddingFormatter must not be null");
		this.embeddingGateway = Objects.requireNonNull(embeddingGateway, "embeddingGateway must not be null");
		this.retrievalService = Objects.requireNonNull(retrievalService, "retrievalService must not be null");
		this.sufficiencyPolicy = Objects.requireNonNull(sufficiencyPolicy, "sufficiencyPolicy must not be null");
		this.answerGateway = Objects.requireNonNull(answerGateway, "answerGateway must not be null");
		this.groundingGateway = Objects.requireNonNull(groundingGateway, "groundingGateway must not be null");
		this.webGroundingGateway = Objects.requireNonNull(
			webGroundingGateway,
			"webGroundingGateway must not be null"
		);
		this.webGroundingPromptFactory = Objects.requireNonNull(
			webGroundingPromptFactory,
			"webGroundingPromptFactory must not be null"
		);
		this.webEvidenceAssembler = Objects.requireNonNull(
			webEvidenceAssembler,
			"webEvidenceAssembler must not be null"
		);
		this.ungroundedAnswerGateway = Objects.requireNonNull(
			ungroundedAnswerGateway,
			"ungroundedAnswerGateway must not be null"
		);
		this.citationAssembler = Objects.requireNonNull(citationAssembler, "citationAssembler must not be null");
		this.finalizationService = Objects.requireNonNull(finalizationService, "finalizationService must not be null");
		this.callbackWake = Objects.requireNonNull(callbackWake, "callbackWake must not be null");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
		this.leaseExtension = requireLeaseExtension(leaseExtension);
		this.answerTimeout = requireModelTimeout(answerTimeout, "answerTimeout");
		this.groundingTimeout = requireModelTimeout(groundingTimeout, "groundingTimeout");
		this.ungroundedAnswerEnabled = ungroundedAnswerEnabled;
	}

	@Override
	@Transactional(propagation = Propagation.NEVER)
	public void process(ClaimedQuestionTask task) {
		Objects.requireNonNull(task, "task must not be null");
		logStageStarted(task, "snapshot");
		Optional<QuestionInputSnapshot> optionalSnapshot = snapshotRepository.findActiveByQuestionId(task.questionId());
		if (cancelled(checkpointService.guardCurrentStage(task, QuestionTaskStage.ANALYZING, leaseExtension))) {
			return;
		}
		QuestionInputSnapshot snapshot = optionalSnapshot.orElseThrow(
			() -> new QuestionTaskFailureException(QuestionTaskFailure.PERMANENT_INPUT)
		);

		RegionContext coarseRegion = regionParser.parse(snapshot.location().address());
		logStageStarted(task, QuestionTaskStage.ANALYZING.databaseValue());
		QueryAnalysis analysis = analyzer.analyze(new ModelQuestionAnalysisInput(
			snapshot.title(),
			snapshot.content(),
			coarseRegion
		));
		if (cancelled(checkpointService.saveAnalysis(task, analysis, leaseExtension))) {
			return;
		}

		String embeddingText = embeddingFormatter.format(snapshot);
		if (cancelled(checkpointService.guardCurrentStage(task, QuestionTaskStage.EMBEDDING, leaseExtension))) {
			return;
		}
		logStageStarted(task, QuestionTaskStage.EMBEDDING.databaseValue());
		QuestionEmbedding embedding = embeddingGateway.embed(embeddingText);
		if (cancelled(checkpointService.saveEmbedding(task, embedding, leaseExtension))) {
			return;
		}

		logStageStarted(task, QuestionTaskStage.RETRIEVING.databaseValue());
		HybridKnowledgeRetrievalResult retrieval = retrievalService.retrieve(
			retrievalRequest(snapshot, analysis, embedding),
			analysis.entityCandidates()
		);
		if (cancelled(checkpointService.guardCurrentStage(task, QuestionTaskStage.RETRIEVING, leaseExtension))) {
			return;
		}
		List<KnowledgeEvidence> evidence = retrievalService.revalidateEvidence(retrieval.candidates());
		GroundingSufficiencyResult sufficiency = sufficiencyPolicy.evaluate(
			evidence,
			analysis.highRiskDomain()
		);
		if (sufficiency.decision() == GroundingSufficiencyResult.Decision.INSUFFICIENT) {
			fallbackToWebOrCompleteInsufficient(
				task,
				QuestionTaskStage.RETRIEVING,
				snapshot,
				coarseRegion,
				embedding,
				analysis,
				retrieval.retrievalConfigVersion(),
				null,
				BigDecimal.ZERO,
				sufficiency.reason().name().toLowerCase(Locale.ROOT)
			);
			return;
		}

		if (cancelled(checkpointService.guardAndAdvance(
			task,
			QuestionTaskStage.RETRIEVING,
			QuestionTaskStage.GENERATING,
			leaseExtension
		))) {
			return;
		}
		LocalAnswerPrompt prompt = localPrompt(snapshot, analysis, evidence);
		if (cancelled(checkpointService.guardCurrentStage(task, QuestionTaskStage.GENERATING, leaseExtension))) {
			return;
		}
		logStageStarted(task, QuestionTaskStage.GENERATING.databaseValue());
		GeneratedAnswer answer = answerGateway.generate(prompt, answerTimeout);
		if (cancelled(checkpointService.guardAndAdvance(
			task,
			QuestionTaskStage.GENERATING,
			QuestionTaskStage.VALIDATING,
			leaseExtension
		))) {
			return;
		}

		if (cancelled(checkpointService.guardCurrentStage(task, QuestionTaskStage.VALIDATING, leaseExtension))) {
			return;
		}
		LocalGroundingRequest groundingRequest = new LocalGroundingRequest(prompt, answer);
		logStageStarted(task, QuestionTaskStage.VALIDATING.databaseValue());
		GroundingValidationResult validation = groundingGateway.validate(groundingRequest, groundingTimeout);
		if (!validation.validation().supported()) {
			if (cancelled(checkpointService.guardCurrentStage(
				task,
				QuestionTaskStage.VALIDATING,
				leaseExtension
			))) {
				return;
			}
			logStageStarted(task, "repairing");
			answer = groundingGateway.repair(groundingRequest, validation.validation(), groundingTimeout);
			groundingRequest = new LocalGroundingRequest(prompt, answer);
			if (cancelled(checkpointService.guardCurrentStage(
				task,
				QuestionTaskStage.VALIDATING,
				leaseExtension
			))) {
				return;
			}
			logStageStarted(task, QuestionTaskStage.VALIDATING.databaseValue());
			validation = groundingGateway.validate(groundingRequest, groundingTimeout);
		}

		if (!validation.validation().supported()) {
			fallbackToWebOrCompleteInsufficient(
				task,
				QuestionTaskStage.VALIDATING,
				snapshot,
				coarseRegion,
				embedding,
				analysis,
				retrieval.retrievalConfigVersion(),
				answer,
				validation.validation().score(),
				answer.fallbackReason() == null
					? "grounding_unsupported_after_repair"
					: answer.fallbackReason()
			);
			return;
		}

		if (cancelled(checkpointService.guardAndAdvance(
			task,
			QuestionTaskStage.VALIDATING,
			QuestionTaskStage.PERSISTING,
			leaseExtension
		))) {
			return;
		}
		if (cancelled(checkpointService.guardCurrentStage(task, QuestionTaskStage.PERSISTING, leaseExtension))) {
			return;
		}
		logStageStarted(task, QuestionTaskStage.PERSISTING.databaseValue());
		List<com.fasterxml.jackson.databind.JsonNode> citationEvidence = citationAssembler.assemble(
			answer.answer(),
			evidence,
			answer.citations()
		);
		QuestionAnswerFinalizationResult result = finalizationService.completeGrounded(
			new GroundedQuestionAnswerFinalization(
				fence(task),
				QuestionAnswerMode.LOCAL_GROUNDED,
				answer.answer(),
				context(
					embedding,
					analysis,
					retrieval.retrievalConfigVersion(),
					answer,
					validation.validation().score(),
					citationEvidence,
					answer.fallbackReason()
				)
			)
		);
		wakeCallbackAfterCommit(result);
	}

	private void logStageStarted(ClaimedQuestionTask task, String stage) {
		log.info(
			"event=question_answer_stage_started questionId={} stage={}",
			task.questionId(),
			stage
		);
	}

	private void fallbackToWebOrCompleteInsufficient(
		ClaimedQuestionTask task,
		QuestionTaskStage currentStage,
		QuestionInputSnapshot snapshot,
		RegionContext trustedCoarseRegion,
		QuestionEmbedding embedding,
		QueryAnalysis analysis,
		String retrievalConfigVersion,
		GeneratedAnswer rejectedLocalAnswer,
		BigDecimal localGroundingScore,
		String localFallbackReason
	) {
		boolean webGroundingEnabled = webGroundingGateway.enabled();
		if (!webGroundingEnabled && !ungroundedAnswerEnabled) {
			completeInsufficient(
				task,
				currentStage,
				embedding,
				analysis,
				retrievalConfigVersion,
				rejectedLocalAnswer,
				localGroundingScore,
				localFallbackReason
			);
			return;
		}

		if (webGroundingEnabled) {
			logStageStarted(task, "web_grounding_preparing");
		}
		Optional<WebGroundingPrompt> optionalPrompt = webGroundingPromptFactory.create(
			snapshot,
			trustedCoarseRegion
		);
		if (optionalPrompt.isEmpty()) {
			completeInsufficient(
				task,
				currentStage,
				embedding,
				analysis,
				retrievalConfigVersion,
				rejectedLocalAnswer,
				localGroundingScore,
				"web_prompt_empty_after_sanitization"
			);
			return;
		}
		WebGroundingPrompt prompt = optionalPrompt.orElseThrow();
		if (!webGroundingEnabled) {
			completeUngroundedOrCompleteInsufficient(
				task,
				currentStage,
				prompt,
				embedding,
				analysis,
				retrievalConfigVersion,
				rejectedLocalAnswer,
				localGroundingScore,
				"web_grounding_disabled"
			);
			return;
		}

		if (cancelled(checkpointService.guardAndAdvance(
			task,
			currentStage,
			QuestionTaskStage.WEB_GROUNDING,
			leaseExtension
		))) {
			return;
		}
		logStageStarted(task, QuestionTaskStage.WEB_GROUNDING.databaseValue());
		Optional<WebGroundedAnswer> optionalWebAnswer;
		try {
			optionalWebAnswer = webGroundingGateway.ground(prompt, WEB_GROUNDING_TIMEOUT);
		}
		catch (QuestionWebGroundingUnavailableException exception) {
			if (!ungroundedAnswerEnabled) {
				throw exception;
			}
			completeUngroundedOrCompleteInsufficient(
				task,
				QuestionTaskStage.WEB_GROUNDING,
				prompt,
				embedding,
				analysis,
				retrievalConfigVersion,
				rejectedLocalAnswer,
				localGroundingScore,
				"web_grounding_" + exception.failureCode().name()
			);
			return;
		}
		if (cancelled(checkpointService.guardCurrentStage(
			task,
			QuestionTaskStage.WEB_GROUNDING,
			leaseExtension
		))) {
			return;
		}
		if (optionalWebAnswer.isEmpty()) {
			completeUngroundedOrCompleteInsufficient(
				task,
				QuestionTaskStage.WEB_GROUNDING,
				prompt,
				embedding,
				analysis,
				retrievalConfigVersion,
				rejectedLocalAnswer,
				localGroundingScore,
				"web_grounding_no_valid_answer"
			);
			return;
		}

		WebGroundedAnswer webAnswer = optionalWebAnswer.get();
		List<com.fasterxml.jackson.databind.JsonNode> webEvidence = webEvidenceAssembler.assemble(webAnswer);
		if (cancelled(checkpointService.guardAndAdvance(
			task,
			QuestionTaskStage.WEB_GROUNDING,
			QuestionTaskStage.PERSISTING,
			leaseExtension
		))) {
			return;
		}
		if (cancelled(checkpointService.guardCurrentStage(
			task,
			QuestionTaskStage.PERSISTING,
			leaseExtension
		))) {
			return;
		}
		logStageStarted(task, QuestionTaskStage.PERSISTING.databaseValue());
		String webFallbackReason = currentStage == QuestionTaskStage.VALIDATING
			? "grounding_unsupported_after_repair"
			: localFallbackReason;
		QuestionAnswerFinalizationResult result = finalizationService.completeGrounded(
			new GroundedQuestionAnswerFinalization(
				fence(task),
				QuestionAnswerMode.WEB_GROUNDED,
				webAnswer.answer(),
				webContext(
					embedding,
					analysis,
					retrievalConfigVersion,
					webAnswer,
					webEvidence,
					webFallbackReason
				)
			)
		);
		wakeCallbackAfterCommit(result);
	}

	private void completeUngroundedOrCompleteInsufficient(
		ClaimedQuestionTask task,
		QuestionTaskStage currentStage,
		WebGroundingPrompt prompt,
		QuestionEmbedding embedding,
		QueryAnalysis analysis,
		String retrievalConfigVersion,
		GeneratedAnswer rejectedLocalAnswer,
		BigDecimal localGroundingScore,
		String fallbackReason
	) {
		if (!ungroundedAnswerEnabled) {
			completeInsufficient(
				task,
				currentStage,
				embedding,
				analysis,
				retrievalConfigVersion,
				rejectedLocalAnswer,
				localGroundingScore,
				fallbackReason
			);
			return;
		}

		log.warn(
			"event=question_answer_ungrounded_fallback questionId={} reason={}",
			task.questionId(),
			fallbackReason
		);
		if (cancelled(checkpointService.guardCurrentStage(task, currentStage, leaseExtension))) {
			return;
		}
		logStageStarted(task, "ungrounded_generating");
		UngroundedAnswer answer = ungroundedAnswerGateway.generate(prompt, answerTimeout);
		completeUngrounded(
			task,
			currentStage,
			embedding,
			analysis,
			retrievalConfigVersion,
			answer.content(),
			answer.provider(),
			answer.model(),
			answer.promptVersion(),
			fallbackReason
		);
	}

	private void completeUngrounded(
		ClaimedQuestionTask task,
		QuestionTaskStage currentStage,
		QuestionEmbedding embedding,
		QueryAnalysis analysis,
		String retrievalConfigVersion,
		String content,
		String provider,
		String model,
		String promptVersion,
		String fallbackReason
	) {
		if (cancelled(checkpointService.guardAndAdvance(
			task,
			currentStage,
			QuestionTaskStage.PERSISTING,
			leaseExtension
		))) {
			return;
		}
		if (cancelled(checkpointService.guardCurrentStage(task, QuestionTaskStage.PERSISTING, leaseExtension))) {
			return;
		}
		logStageStarted(task, QuestionTaskStage.PERSISTING.databaseValue());
		QuestionAnswerFinalizationResult result = finalizationService.completeUngrounded(
			new UngroundedQuestionAnswerFinalization(
				fence(task),
				content,
				ungroundedContext(
					embedding,
					analysis,
					retrievalConfigVersion,
					provider,
					model,
					promptVersion,
					fallbackReason
				)
			)
		);
		wakeCallbackAfterCommit(result);
	}

	private void completeInsufficient(
		ClaimedQuestionTask task,
		QuestionTaskStage currentStage,
		QuestionEmbedding embedding,
		QueryAnalysis analysis,
		String retrievalConfigVersion,
		GeneratedAnswer answer,
		BigDecimal groundingScore,
		String fallbackReason
	) {
		if (cancelled(checkpointService.guardAndAdvance(
			task,
			currentStage,
			QuestionTaskStage.PERSISTING,
			leaseExtension
		))) {
			return;
		}
		if (cancelled(checkpointService.guardCurrentStage(task, QuestionTaskStage.PERSISTING, leaseExtension))) {
			return;
		}
		logStageStarted(task, QuestionTaskStage.PERSISTING.databaseValue());
		finalizationService.completeInsufficient(new InsufficientQuestionAnswerFinalization(
			fence(task),
			context(
				embedding,
				analysis,
				retrievalConfigVersion,
				answer,
				groundingScore,
				List.of(),
				fallbackReason
			)
		));
	}

	private QuestionAnswerFinalizationContext context(
		QuestionEmbedding embedding,
		QueryAnalysis analysis,
		String retrievalConfigVersion,
		GeneratedAnswer answer,
		BigDecimal groundingScore,
		List<com.fasterxml.jackson.databind.JsonNode> evidence,
		String fallbackReason
	) {
		return new QuestionAnswerFinalizationContext(
			embedding.values(),
			embedding.model(),
			shinhan.fibri.ieum.ai.question.retrieval.GeoScope.valueOf(analysis.geoScope().name()),
			analysis.confidence(),
			regionJson(analysis.regionContext()),
			answer == null ? null : answer.provider(),
			answer == null ? null : answer.model(),
			retrievalConfigVersion,
			fallbackReason,
			answer == null ? null : answer.promptVersion(),
			groundingScore,
			evidence
		);
	}

	private QuestionAnswerFinalizationContext ungroundedContext(
		QuestionEmbedding embedding,
		QueryAnalysis analysis,
		String retrievalConfigVersion,
		String provider,
		String model,
		String promptVersion,
		String fallbackReason
	) {
		return new QuestionAnswerFinalizationContext(
			embedding.values(),
			embedding.model(),
			shinhan.fibri.ieum.ai.question.retrieval.GeoScope.valueOf(analysis.geoScope().name()),
			analysis.confidence(),
			regionJson(analysis.regionContext()),
			provider,
			model,
			retrievalConfigVersion,
			fallbackReason,
			promptVersion,
			BigDecimal.ZERO,
			List.of()
		);
	}

	private QuestionAnswerFinalizationContext webContext(
		QuestionEmbedding embedding,
		QueryAnalysis analysis,
		String retrievalConfigVersion,
		WebGroundedAnswer answer,
		List<com.fasterxml.jackson.databind.JsonNode> evidence,
		String fallbackReason
	) {
		return new QuestionAnswerFinalizationContext(
			embedding.values(),
			embedding.model(),
			shinhan.fibri.ieum.ai.question.retrieval.GeoScope.valueOf(analysis.geoScope().name()),
			analysis.confidence(),
			regionJson(analysis.regionContext()),
			answer.provider(),
			answer.model(),
			retrievalConfigVersion,
			fallbackReason,
			answer.promptVersion(),
			answer.groundingScore(),
			evidence
		);
	}

	private VectorKnowledgeRetrievalRequest retrievalRequest(
		QuestionInputSnapshot snapshot,
		QueryAnalysis analysis,
		QuestionEmbedding embedding
	) {
		return new VectorKnowledgeRetrievalRequest(
			embedding.values(),
			shinhan.fibri.ieum.ai.question.retrieval.GeoScope.valueOf(analysis.geoScope().name()),
			new GeoPoint(snapshot.location().latitude(), snapshot.location().longitude()),
			new shinhan.fibri.ieum.ai.question.retrieval.RegionContext(
				analysis.regionContext().sido(),
				analysis.regionContext().sigungu()
			)
		);
	}

	private LocalAnswerPrompt localPrompt(
		QuestionInputSnapshot snapshot,
		QueryAnalysis analysis,
		List<? extends KnowledgeEvidence> evidence
	) {
		List<LocalAnswerEvidence> localEvidence = new ArrayList<>(evidence.size());
		for (int index = 0; index < evidence.size(); index++) {
			KnowledgeEvidence item = evidence.get(index);
			localEvidence.add(new LocalAnswerEvidence(index, item.title(), item.excerpt(), item.sourceType()));
		}
		RegionContext region = analysis.regionContext();
		LocalAnswerRegion localRegion = region.isEmpty()
			? LocalAnswerRegion.empty()
			: new LocalAnswerRegion(
				region.country(),
				region.sido(),
				region.sigungu(),
				region.eupMyeonDong()
			);
		return new LocalAnswerPrompt(snapshot.title(), snapshot.content(), localRegion, localEvidence);
	}

	private ObjectNode regionJson(RegionContext region) {
		ObjectNode json = objectMapper.createObjectNode();
		putNullable(json, "country", region.country());
		putNullable(json, "sido", region.sido());
		putNullable(json, "sigungu", region.sigungu());
		putNullable(json, "eupMyeonDong", region.eupMyeonDong());
		putNullable(json, "place", region.place());
		return json;
	}

	private void putNullable(ObjectNode json, String field, String value) {
		if (value == null) {
			json.putNull(field);
			return;
		}
		json.put(field, value);
	}

	private void wakeCallbackAfterCommit(QuestionAnswerFinalizationResult result) {
		if (!result.hasAnswer()) {
			return;
		}
		try {
			callbackWake.wake(result.questionId());
		}
		catch (RuntimeException exception) {
			log.warn(
				"Question answer callback wake failed questionId={} errorType={}",
				result.questionId(),
				exception.getClass().getSimpleName()
			);
		}
	}

	private QuestionTaskFence fence(ClaimedQuestionTask task) {
		return new QuestionTaskFence(task.questionId(), task.workerId(), task.leaseToken());
	}

	private boolean cancelled(QuestionCheckpointResult result) {
		return result == QuestionCheckpointResult.CANCELLED;
	}

	private Duration requireLeaseExtension(Duration value) {
		if (value == null || value.compareTo(MINIMUM_LEASE_EXTENSION) < 0) {
			throw new IllegalArgumentException("leaseExtension must be at least 65 seconds");
		}
		return value;
	}

	private Duration requireModelTimeout(Duration value, String field) {
		if (!REQUIRED_MODEL_TIMEOUT.equals(value)) {
			throw new IllegalArgumentException(field + " must be 30 seconds");
		}
		return value;
	}
}
