package shinhan.fibri.ieum.ai.question.service;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import shinhan.fibri.ieum.ai.question.checkpoint.StaleQuestionCheckpointException;
import shinhan.fibri.ieum.ai.question.finalization.StaleQuestionTaskFinalizationException;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerProviderFailureCode;
import shinhan.fibri.ieum.ai.question.generation.QuestionGenerationUnavailableException;
import shinhan.fibri.ieum.ai.question.grounding.QuestionGroundingUnavailableException;
import shinhan.fibri.ieum.ai.question.repository.ClaimedQuestionTask;
import shinhan.fibri.ieum.ai.question.repository.QuestionTaskWorkRepository;
import shinhan.fibri.ieum.ai.question.webgrounding.QuestionWebGroundingUnavailableException;
import shinhan.fibri.ieum.ai.question.webgrounding.WebGroundingFailureCode;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.exception.SdkServiceException;

public class QuestionAnswerTaskProcessor {

	private static final Logger log = LoggerFactory.getLogger(QuestionAnswerTaskProcessor.class);

	private final QuestionTaskWorkRepository repository;
	private final QuestionAnswerOrchestrator orchestrator;
	private final String workerId;
	private final Duration taskLease;
	private final int maxAttempts;

	public QuestionAnswerTaskProcessor(
		QuestionTaskWorkRepository repository,
		QuestionAnswerOrchestrator orchestrator,
		String workerId,
		Duration taskLease,
		int maxAttempts
	) {
		this.repository = repository;
		this.orchestrator = orchestrator;
		this.workerId = workerId;
		this.taskLease = taskLease;
		this.maxAttempts = maxAttempts;
	}

	public void process(long questionId) {
		repository.claimByQuestionId(questionId, workerId, taskLease, maxAttempts)
			.ifPresent(this::processClaim);
	}

	private void processClaim(ClaimedQuestionTask claim) {
		long startedAt = System.nanoTime();
		log.info(
			"event=question_answer_claimed questionId={} workerId={} attempts={}",
			claim.questionId(), claim.workerId(), claim.attempts()
		);
		try {
			orchestrator.process(claim);
			log.info(
				"event=question_answer_completed questionId={} workerId={} attempts={} durationMs={}",
				claim.questionId(), claim.workerId(), claim.attempts(), elapsedMs(startedAt)
			);
		}
		catch (RuntimeException exception) {
			QuestionTaskFailure failure = classify(exception);
			logProviderFailure(claim, exception, failure);
			if (failure.disposition() == QuestionTaskFailureDisposition.DISCARD) {
				log.warn(
					"event=question_answer_stale_discarded questionId={} workerId={} attempts={} failure={} durationMs={}",
					claim.questionId(), claim.workerId(), claim.attempts(), failure, elapsedMs(startedAt)
				);
				return;
			}
			if (failure.disposition() == QuestionTaskFailureDisposition.DEAD
				|| claim.attempts() >= maxAttempts) {
				boolean transitioned = repository.markDead(
					claim.questionId(),
					claim.workerId(),
					claim.leaseToken(),
					failure
				);
				logFailureTransition(claim, failure, transitioned, true, null, startedAt);
				return;
			}
			Duration retryDelay = retryDelay(claim.attempts());
			boolean transitioned = repository.markRetry(
				claim.questionId(),
				claim.workerId(),
				claim.leaseToken(),
				retryDelay,
				failure
			);
			logFailureTransition(claim, failure, transitioned, false, retryDelay, startedAt);
		}
	}

	private void logProviderFailure(
		ClaimedQuestionTask claim,
		RuntimeException exception,
		QuestionTaskFailure failure
	) {
		QuestionWebGroundingUnavailableException webGroundingFailure = findWebGroundingFailure(exception);
		if (webGroundingFailure == null) {
			return;
		}
		Object httpStatus = webGroundingFailure.failureCode() == WebGroundingFailureCode.rate_limited
			? 429
			: "unknown";
		log.warn(
			"event=question_answer_provider_failure questionId={} workerId={} attempts={} stage=web_grounding provider=gemini_google_search httpStatus={} failure={}",
			claim.questionId(),
			claim.workerId(),
			claim.attempts(),
			httpStatus,
			failure
		);
	}

	private QuestionWebGroundingUnavailableException findWebGroundingFailure(RuntimeException exception) {
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Throwable current = exception;
		while (current != null && visited.add(current)) {
			if (current instanceof QuestionWebGroundingUnavailableException webGroundingFailure) {
				return webGroundingFailure;
			}
			current = current.getCause();
		}
		return null;
	}

	private void logFailureTransition(
		ClaimedQuestionTask claim,
		QuestionTaskFailure failure,
		boolean transitioned,
		boolean dead,
		Duration retryDelay,
		long startedAt
	) {
		if (!transitioned) {
			log.warn(
				"event=question_answer_stale_discarded questionId={} workerId={} attempts={} failure={} durationMs={}",
				claim.questionId(), claim.workerId(), claim.attempts(), failure, elapsedMs(startedAt)
			);
			return;
		}
		if (dead) {
			log.error(
				"event=question_answer_dead questionId={} workerId={} attempts={} failure={} durationMs={}",
				claim.questionId(), claim.workerId(), claim.attempts(), failure, elapsedMs(startedAt)
			);
			return;
		}
		log.warn(
			"event=question_answer_retry_scheduled questionId={} workerId={} attempts={} failure={} retryDelayMs={} durationMs={}",
			claim.questionId(), claim.workerId(), claim.attempts(), failure, retryDelay.toMillis(), elapsedMs(startedAt)
		);
	}

	private long elapsedMs(long startedAt) {
		return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
	}

	private QuestionTaskFailure classify(RuntimeException exception) {
		QuestionTaskFailure explicitFailure = null;
		boolean embeddingUnavailable = false;
		QuestionTaskFailure generationFailure = null;
		QuestionTaskFailure providerFailure = null;
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Throwable current = exception;
		while (current != null && visited.add(current)) {
			if (current instanceof StaleQuestionCheckpointException
				|| current instanceof StaleQuestionTaskFinalizationException) {
				return QuestionTaskFailure.STALE_FENCE;
			}
			if (current instanceof QuestionTaskFailureException classified
				&& explicitFailure == null) {
				explicitFailure = classified.failure();
			}
			if (current instanceof QuestionWebGroundingUnavailableException webGrounding) {
				QuestionTaskFailure webGroundingFailure = classifyWebGroundingFailure(webGrounding);
				if (webGroundingFailure == QuestionTaskFailure.PERMANENT_CONFIGURATION) {
					if (explicitFailure == null) {
						explicitFailure = webGroundingFailure;
					}
				}
				else {
					providerFailure = preferProviderFailure(
						providerFailure,
						webGroundingFailure
					);
				}
			}
			if (current instanceof EmbeddingUnavailableException) {
				embeddingUnavailable = true;
			}
			if (current instanceof QuestionGenerationUnavailableException generation) {
				generationFailure = preferProviderFailure(
					generationFailure,
					classifyProviderFailures(
						generation.primaryFailure(),
						generation.fallbackFailure()
					)
				);
			}
			if (current instanceof QuestionGroundingUnavailableException grounding) {
				generationFailure = preferProviderFailure(
					generationFailure,
					classifyProviderFailures(
						grounding.primaryFailure(),
						grounding.fallbackFailure()
					)
				);
			}
			if (current instanceof SdkServiceException serviceException) {
				providerFailure = preferProviderFailure(
					providerFailure,
					classifyServiceFailure(serviceException)
				);
			}
			if (current instanceof TimeoutException || current instanceof SocketTimeoutException) {
				providerFailure = preferProviderFailure(
					providerFailure,
					QuestionTaskFailure.PROVIDER_TIMEOUT
				);
			}
			if (current instanceof SdkException sdkException && sdkException.retryable()) {
				providerFailure = preferProviderFailure(
					providerFailure,
					QuestionTaskFailure.PROVIDER_UNAVAILABLE
				);
			}
			current = current.getCause();
		}
		if (explicitFailure != null) {
			return explicitFailure;
		}
		if (embeddingUnavailable) {
			return QuestionTaskFailure.EMBEDDING_UNAVAILABLE;
		}
		if (generationFailure != null) {
			return generationFailure;
		}
		if (providerFailure != null) {
			return providerFailure;
		}
		return QuestionTaskFailure.UNEXPECTED_TRANSIENT;
	}

	private QuestionTaskFailure classifyWebGroundingFailure(
		QuestionWebGroundingUnavailableException exception
	) {
		return switch (exception.failureCode()) {
			case timeout -> QuestionTaskFailure.PROVIDER_TIMEOUT;
			case rate_limited -> QuestionTaskFailure.WEB_GROUNDING_RATE_LIMITED;
			case provider_unavailable -> QuestionTaskFailure.PROVIDER_UNAVAILABLE;
			case permanent_configuration -> QuestionTaskFailure.PERMANENT_CONFIGURATION;
		};
	}

	private QuestionTaskFailure preferProviderFailure(
		QuestionTaskFailure current,
		QuestionTaskFailure candidate
	) {
		if (current == null || providerFailurePriority(candidate) > providerFailurePriority(current)) {
			return candidate;
		}
		return current;
	}

	private int providerFailurePriority(QuestionTaskFailure failure) {
		return switch (failure) {
			case PROVIDER_RATE_LIMITED -> 4;
			case WEB_GROUNDING_RATE_LIMITED -> 4;
			case PROVIDER_TIMEOUT -> 3;
			case PROVIDER_UNAVAILABLE -> 2;
			case GENERATION_INVALID_OUTPUT -> 1;
			default -> 0;
		};
	}

	private QuestionTaskFailure classifyProviderFailures(
		LocalAnswerProviderFailureCode primaryFailure,
		LocalAnswerProviderFailureCode fallbackFailure
	) {
		if (hasProviderFailure(
			primaryFailure,
			fallbackFailure,
			LocalAnswerProviderFailureCode.rate_limited
		)) {
			return QuestionTaskFailure.PROVIDER_RATE_LIMITED;
		}
		if (hasProviderFailure(
			primaryFailure,
			fallbackFailure,
			LocalAnswerProviderFailureCode.timeout
		)) {
			return QuestionTaskFailure.PROVIDER_TIMEOUT;
		}
		if (hasProviderFailure(
			primaryFailure,
			fallbackFailure,
			LocalAnswerProviderFailureCode.provider_unavailable
		)) {
			return QuestionTaskFailure.PROVIDER_UNAVAILABLE;
		}
		return QuestionTaskFailure.GENERATION_INVALID_OUTPUT;
	}

	private boolean hasProviderFailure(
		LocalAnswerProviderFailureCode primaryFailure,
		LocalAnswerProviderFailureCode fallbackFailure,
		LocalAnswerProviderFailureCode expected
	) {
		return primaryFailure == expected || fallbackFailure == expected;
	}

	private QuestionTaskFailure classifyServiceFailure(SdkServiceException exception) {
		if (exception.isThrottlingException() || exception.statusCode() == 429) {
			return QuestionTaskFailure.PROVIDER_RATE_LIMITED;
		}
		if (exception.statusCode() == 408 || exception.statusCode() == 504) {
			return QuestionTaskFailure.PROVIDER_TIMEOUT;
		}
		if (exception.statusCode() >= 500) {
			return QuestionTaskFailure.PROVIDER_UNAVAILABLE;
		}
		return QuestionTaskFailure.UNEXPECTED_TRANSIENT;
	}

	private Duration retryDelay(int attempt) {
		return switch (attempt) {
			case 1 -> Duration.ofSeconds(10);
			case 2 -> Duration.ofSeconds(30);
			case 3 -> Duration.ofMinutes(2);
			case 4 -> Duration.ofMinutes(10);
			default -> throw new IllegalStateException("unsupported retry attempt: " + attempt);
		};
	}
}
