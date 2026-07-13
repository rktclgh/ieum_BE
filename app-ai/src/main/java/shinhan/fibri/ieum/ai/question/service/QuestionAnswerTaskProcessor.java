package shinhan.fibri.ieum.ai.question.service;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import shinhan.fibri.ieum.ai.question.checkpoint.StaleQuestionCheckpointException;
import shinhan.fibri.ieum.ai.question.finalization.StaleQuestionTaskFinalizationException;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerProviderFailureCode;
import shinhan.fibri.ieum.ai.question.generation.QuestionGenerationUnavailableException;
import shinhan.fibri.ieum.ai.question.grounding.QuestionGroundingUnavailableException;
import shinhan.fibri.ieum.ai.question.repository.ClaimedQuestionTask;
import shinhan.fibri.ieum.ai.question.repository.QuestionTaskWorkRepository;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.exception.SdkServiceException;

public class QuestionAnswerTaskProcessor {

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
		try {
			orchestrator.process(claim);
		}
		catch (RuntimeException exception) {
			QuestionTaskFailure failure = classify(exception);
			if (failure.disposition() == QuestionTaskFailureDisposition.DISCARD) {
				return;
			}
			if (failure.disposition() == QuestionTaskFailureDisposition.DEAD
				|| claim.attempts() >= maxAttempts) {
				repository.markDead(
					claim.questionId(),
					claim.workerId(),
					claim.leaseToken(),
					failure
				);
				return;
			}
			repository.markRetry(
				claim.questionId(),
				claim.workerId(),
				claim.leaseToken(),
				retryDelay(claim.attempts()),
				failure
			);
		}
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
