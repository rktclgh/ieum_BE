package shinhan.fibri.ieum.ai.question.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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
import software.amazon.awssdk.core.exception.SdkServiceException;

class QuestionAnswerTaskProcessorTest {

	private final QuestionTaskWorkRepository repository = mock(QuestionTaskWorkRepository.class);
	private final QuestionAnswerOrchestrator orchestrator = mock(QuestionAnswerOrchestrator.class);
	private final QuestionAnswerTaskProcessor processor = new QuestionAnswerTaskProcessor(
		repository,
		orchestrator,
		"worker-1",
		Duration.ofMinutes(2),
		5
	);

	@Test
	void claimsTheQueuedQuestionIdAndHandsTheClaimToTheOrchestratorOnce() {
		ClaimedQuestionTask claim = new ClaimedQuestionTask(
			42L,
			"worker-1",
			UUID.randomUUID(),
			OffsetDateTime.now().plusMinutes(2),
			1
		);
		when(repository.claimByQuestionId(42L, "worker-1", Duration.ofMinutes(2), 5))
			.thenReturn(Optional.of(claim));

		processor.process(42L);

		verify(repository).claimByQuestionId(42L, "worker-1", Duration.ofMinutes(2), 5);
		verify(orchestrator).process(claim);
	}

	@Test
	void logsClaimAndCompletionWithoutQuestionOrProviderPayloads() {
		ClaimedQuestionTask claim = new ClaimedQuestionTask(
			42L,
			"worker-1",
			UUID.fromString("11111111-1111-1111-1111-111111111111"),
			OffsetDateTime.now().plusMinutes(2),
			1
		);
		when(repository.claimByQuestionId(42L, "worker-1", Duration.ofMinutes(2), 5))
			.thenReturn(Optional.of(claim));
		ListAppender<ILoggingEvent> logs = captureLogs();

		processor.process(42L);

		assertThat(messages(logs))
			.anyMatch(message -> message.contains(
				"event=question_answer_claimed questionId=42 workerId=worker-1 attempts=1"
			))
			.anyMatch(message -> message.contains(
				"event=question_answer_completed questionId=42 workerId=worker-1 attempts=1"
			))
			.noneMatch(message -> message.contains("question content") || message.contains("provider response"));
	}

	@Test
	void logsOnlyTheAllowlistedFailureWhenRetryIsScheduled() {
		ClaimedQuestionTask claim = claim(1);
		when(repository.claimByQuestionId(42L, "worker-1", Duration.ofMinutes(2), 5))
			.thenReturn(Optional.of(claim));
		doThrow(new RuntimeException("raw provider response secret"))
			.when(orchestrator).process(claim);
		when(repository.markRetry(
			42L,
			"worker-1",
			claim.leaseToken(),
			Duration.ofSeconds(10),
			QuestionTaskFailure.UNEXPECTED_TRANSIENT
		)).thenReturn(true);
		ListAppender<ILoggingEvent> logs = captureLogs();

		processor.process(42L);

		assertThat(messages(logs))
			.anyMatch(message -> message.contains(
				"event=question_answer_retry_scheduled questionId=42 workerId=worker-1 attempts=1 failure=UNEXPECTED_TRANSIENT retryDelayMs=10000"
			))
			.noneMatch(message -> message.contains("raw provider response secret"));
	}

	@Test
	void logsTheSafeWebGroundingProviderMetadataForQuotaFailures() {
		ClaimedQuestionTask claim = claim(1);
		QuestionWebGroundingUnavailableException failure = new QuestionWebGroundingUnavailableException(
			WebGroundingFailureCode.rate_limited
		);
		when(repository.claimByQuestionId(42L, "worker-1", Duration.ofMinutes(2), 5))
			.thenReturn(Optional.of(claim));
		doThrow(failure).when(orchestrator).process(claim);
		when(repository.markRetry(
			42L,
			"worker-1",
			claim.leaseToken(),
			Duration.ofSeconds(10),
			QuestionTaskFailure.WEB_GROUNDING_RATE_LIMITED
		)).thenReturn(true);
		ListAppender<ILoggingEvent> logs = captureLogs();

		processor.process(42L);

		assertThat(messages(logs))
			.anyMatch(message -> message.contains(
				"event=question_answer_provider_failure questionId=42 workerId=worker-1 attempts=1 stage=web_grounding provider=gemini_google_search httpStatus=429 failure=WEB_GROUNDING_RATE_LIMITED"
			))
			.noneMatch(message -> message.contains("raw provider response"));
	}

	@Test
	void doesNothingWhenAnotherWorkerOwnsOrTheTicketIsNotDue() {
		when(repository.claimByQuestionId(42L, "worker-1", Duration.ofMinutes(2), 5))
			.thenReturn(Optional.empty());

		processor.process(42L);

		verifyNoInteractions(orchestrator);
	}

	@ParameterizedTest
	@CsvSource({
		"1, 10",
		"2, 30",
		"3, 120",
		"4, 600"
	})
	void retriesUnknownRuntimeFailuresWithTheCanonicalSafeErrorAndAttemptBackoff(
		int attempts,
		long expectedBackoffSeconds
	) {
		ClaimedQuestionTask claim = claim(attempts);
		when(repository.claimByQuestionId(42L, "worker-1", Duration.ofMinutes(2), 5))
			.thenReturn(Optional.of(claim));
		doThrow(new RuntimeException("raw question and provider response must not be persisted"))
			.when(orchestrator).process(claim);

		processor.process(42L);

		verify(repository).markRetry(
			42L,
			"worker-1",
			claim.leaseToken(),
			Duration.ofSeconds(expectedBackoffSeconds),
			QuestionTaskFailure.UNEXPECTED_TRANSIENT
		);
		verify(repository, never()).markDead(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void marksTheCurrentFenceDeadWhenTheFinalAttemptFails() {
		ClaimedQuestionTask claim = claim(5);
		when(repository.claimByQuestionId(42L, "worker-1", Duration.ofMinutes(2), 5))
			.thenReturn(Optional.of(claim));
		doThrow(new RuntimeException("sensitive provider payload"))
			.when(orchestrator).process(claim);

		processor.process(42L);

		verify(repository).markDead(
			42L,
			"worker-1",
			claim.leaseToken(),
			QuestionTaskFailure.UNEXPECTED_TRANSIENT
		);
		verify(repository, never()).markRetry(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	@ParameterizedTest
	@EnumSource(value = QuestionTaskFailure.class, names = {
		"PERMANENT_INPUT",
		"PERMANENT_CONFIGURATION"
	})
	void marksExplicitPermanentFailuresDeadWithoutConsumingRetryBudget(QuestionTaskFailure failure) {
		ClaimedQuestionTask claim = claim(1);
		when(repository.claimByQuestionId(42L, "worker-1", Duration.ofMinutes(2), 5))
			.thenReturn(Optional.of(claim));
		doThrow(new QuestionTaskFailureException(failure))
			.when(orchestrator).process(claim);

		processor.process(42L);

		verify(repository).markDead(42L, "worker-1", claim.leaseToken(), failure);
		verify(repository, never()).markRetry(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void retriesEmbeddingUnavailabilityWithItsAllowlistedFailure() {
		ClaimedQuestionTask claim = claim(1);
		when(repository.claimByQuestionId(42L, "worker-1", Duration.ofMinutes(2), 5))
			.thenReturn(Optional.of(claim));
		doThrow(new EmbeddingUnavailableException("raw provider response must not be persisted"))
			.when(orchestrator).process(claim);

		processor.process(42L);

		verify(repository).markRetry(
			42L,
			"worker-1",
			claim.leaseToken(),
			Duration.ofSeconds(10),
			QuestionTaskFailure.EMBEDDING_UNAVAILABLE
		);
	}

	@ParameterizedTest
	@EnumSource(value = QuestionTaskFailure.class, names = {
		"PROVIDER_TIMEOUT",
		"PROVIDER_RATE_LIMITED",
		"PROVIDER_UNAVAILABLE"
	})
	void retriesExplicitProviderFailuresWithOnlyTheAllowlistedFailure(QuestionTaskFailure failure) {
		ClaimedQuestionTask claim = claim(1);
		when(repository.claimByQuestionId(42L, "worker-1", Duration.ofMinutes(2), 5))
			.thenReturn(Optional.of(claim));
		doThrow(new QuestionTaskFailureException(failure, new RuntimeException("raw secret")))
			.when(orchestrator).process(claim);

		processor.process(42L);

		verify(repository).markRetry(
			42L,
			"worker-1",
			claim.leaseToken(),
			Duration.ofSeconds(10),
			failure
		);
	}

	@Test
	void classifiesANestedTimeoutWithoutPersistingTheExceptionMessage() {
		assertTransientClassification(
			new IllegalStateException(new TimeoutException("raw timeout payload")),
			QuestionTaskFailure.PROVIDER_TIMEOUT
		);
	}

	@Test
	void classifiesProvider429AsRateLimited() {
		assertTransientClassification(
			SdkServiceException.builder().statusCode(429).message("raw rate-limit payload").build(),
			QuestionTaskFailure.PROVIDER_RATE_LIMITED
		);
	}

	@Test
	void classifiesProvider5xxAsUnavailable() {
		assertTransientClassification(
			SdkServiceException.builder().statusCode(503).message("raw provider payload").build(),
			QuestionTaskFailure.PROVIDER_UNAVAILABLE
		);
	}

	@Test
	void classifiesDualProviderTimeoutWithoutUsingProviderPayloads() {
		assertGenerationFailureClassification(
			LocalAnswerProviderFailureCode.timeout,
			LocalAnswerProviderFailureCode.provider_unavailable,
			QuestionTaskFailure.PROVIDER_TIMEOUT
		);
	}

	@Test
	void classifiesDualProviderRateLimitWithoutUsingProviderPayloads() {
		assertGenerationFailureClassification(
			LocalAnswerProviderFailureCode.invalid_output,
			LocalAnswerProviderFailureCode.rate_limited,
			QuestionTaskFailure.PROVIDER_RATE_LIMITED
		);
	}

	@Test
	void classifiesProviderUnavailabilityAheadOfInvalidOutput() {
		assertGenerationFailureClassification(
			LocalAnswerProviderFailureCode.invalid_output,
			LocalAnswerProviderFailureCode.provider_unavailable,
			QuestionTaskFailure.PROVIDER_UNAVAILABLE
		);
	}

	@Test
	void classifiesDualInvalidOrEmptyOutputsWithoutMislabelingProviderAvailability() {
		assertGenerationFailureClassification(
			LocalAnswerProviderFailureCode.invalid_output,
			LocalAnswerProviderFailureCode.empty_response,
			QuestionTaskFailure.GENERATION_INVALID_OUTPUT
		);
	}

	@Test
	void classifiesGroundingRateLimitAheadOfInvalidOutput() {
		assertGroundingFailureClassification(
			LocalAnswerProviderFailureCode.invalid_output,
			LocalAnswerProviderFailureCode.rate_limited,
			QuestionTaskFailure.PROVIDER_RATE_LIMITED
		);
	}

	@Test
	void classifiesGroundingTimeoutAheadOfProviderUnavailability() {
		assertGroundingFailureClassification(
			LocalAnswerProviderFailureCode.timeout,
			LocalAnswerProviderFailureCode.provider_unavailable,
			QuestionTaskFailure.PROVIDER_TIMEOUT
		);
	}

	@Test
	void classifiesGroundingProviderUnavailabilityAheadOfInvalidOutput() {
		assertGroundingFailureClassification(
			LocalAnswerProviderFailureCode.invalid_output,
			LocalAnswerProviderFailureCode.provider_unavailable,
			QuestionTaskFailure.PROVIDER_UNAVAILABLE
		);
	}

	@Test
	void classifiesGroundingInvalidOrEmptyOutputsAsGenerationInvalidOutput() {
		assertGroundingFailureClassification(
			LocalAnswerProviderFailureCode.invalid_output,
			LocalAnswerProviderFailureCode.empty_response,
			QuestionTaskFailure.GENERATION_INVALID_OUTPUT
		);
	}

	@Test
	void wrappedGroundingFailureKeepsItsAllowlistedClassification() {
		assertTransientClassification(
			new IllegalStateException(new QuestionGroundingUnavailableException(
				LocalAnswerProviderFailureCode.invalid_output,
				LocalAnswerProviderFailureCode.rate_limited
			)),
			QuestionTaskFailure.PROVIDER_RATE_LIMITED
		);
	}

	@ParameterizedTest
	@CsvSource({
		"timeout, PROVIDER_TIMEOUT",
		"rate_limited, WEB_GROUNDING_RATE_LIMITED",
		"provider_unavailable, PROVIDER_UNAVAILABLE",
		"permanent_configuration, PERMANENT_CONFIGURATION"
	})
	void classifiesDirectWebGroundingFailuresWithOnlyTheCanonicalFailure(
		WebGroundingFailureCode failureCode,
		QuestionTaskFailure expectedFailure
	) {
		assertWebGroundingFailureClassification(
			new QuestionWebGroundingUnavailableException(failureCode),
			expectedFailure
		);
	}

	@ParameterizedTest
	@CsvSource({
		"timeout, PROVIDER_TIMEOUT",
		"rate_limited, WEB_GROUNDING_RATE_LIMITED",
		"provider_unavailable, PROVIDER_UNAVAILABLE",
		"permanent_configuration, PERMANENT_CONFIGURATION"
	})
	void classifiesWrappedWebGroundingFailuresWithoutPersistingRawProviderMessages(
		WebGroundingFailureCode failureCode,
		QuestionTaskFailure expectedFailure
	) {
		assertWebGroundingFailureClassification(
			new IllegalStateException(
				"raw Gemini provider payload must not be persisted",
				new QuestionWebGroundingUnavailableException(failureCode)
			),
			expectedFailure
		);
	}

	@Test
	void wrappedStaleFailureStillDiscardsWithoutAStateTransition() {
		ClaimedQuestionTask claim = claim(1);
		when(repository.claimByQuestionId(42L, "worker-1", Duration.ofMinutes(2), 5))
			.thenReturn(Optional.of(claim));
		doThrow(new IllegalStateException(new StaleQuestionCheckpointException(42L)))
			.when(orchestrator).process(claim);

		processor.process(42L);

		verify(repository, never()).markRetry(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
		verify(repository, never()).markDead(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void wrappedExplicitPermanentFailureStillDiesImmediately() {
		ClaimedQuestionTask claim = claim(1);
		when(repository.claimByQuestionId(42L, "worker-1", Duration.ofMinutes(2), 5))
			.thenReturn(Optional.of(claim));
		doThrow(new IllegalStateException(
			new QuestionTaskFailureException(QuestionTaskFailure.PERMANENT_CONFIGURATION)
		)).when(orchestrator).process(claim);

		processor.process(42L);

		verify(repository).markDead(
			42L,
			"worker-1",
			claim.leaseToken(),
			QuestionTaskFailure.PERMANENT_CONFIGURATION
		);
	}

	@Test
	void wrappedEmbeddingFailureKeepsItsAllowlistedClassification() {
		assertTransientClassification(
			new IllegalStateException(new EmbeddingUnavailableException("raw embedding payload")),
			QuestionTaskFailure.EMBEDDING_UNAVAILABLE
		);
	}

	@Test
	void wrappedGenerationFailureKeepsItsAllowlistedClassification() {
		assertTransientClassification(
			new IllegalStateException(new QuestionGenerationUnavailableException(
				LocalAnswerProviderFailureCode.invalid_output,
				LocalAnswerProviderFailureCode.rate_limited
			)),
			QuestionTaskFailure.PROVIDER_RATE_LIMITED
		);
	}

	@Test
	void cyclicCauseChainTerminatesAsAnUnexpectedTransientFailure() {
		assertTransientClassification(new CyclicRuntimeException(), QuestionTaskFailure.UNEXPECTED_TRANSIENT);
	}

	@ParameterizedTest
	@EnumSource(value = StaleFailure.class)
	void discardsStaleCheckpointAndFinalizationFailuresWithoutAStateTransition(StaleFailure staleFailure) {
		ClaimedQuestionTask claim = claim(1);
		when(repository.claimByQuestionId(42L, "worker-1", Duration.ofMinutes(2), 5))
			.thenReturn(Optional.of(claim));
		doThrow(staleFailure.exception()).when(orchestrator).process(claim);

		processor.process(42L);

		verify(repository, never()).markRetry(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
		verify(repository, never()).markDead(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void doesNotRecursivelyRetryWhenTheFailureTransitionFenceIsStale() {
		ClaimedQuestionTask claim = claim(1);
		when(repository.claimByQuestionId(42L, "worker-1", Duration.ofMinutes(2), 5))
			.thenReturn(Optional.of(claim));
		doThrow(new RuntimeException("transient"))
			.when(orchestrator).process(claim);
		when(repository.markRetry(
			42L,
			"worker-1",
			claim.leaseToken(),
			Duration.ofSeconds(10),
			QuestionTaskFailure.UNEXPECTED_TRANSIENT
		)).thenReturn(false);

		processor.process(42L);

		verify(orchestrator).process(claim);
		verify(repository).markRetry(
			42L,
			"worker-1",
			claim.leaseToken(),
			Duration.ofSeconds(10),
			QuestionTaskFailure.UNEXPECTED_TRANSIENT
		);
		verify(repository, never()).markDead(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void usesTheCanonicalWorkerFenceReturnedByClaimForFailureTransitions() {
		QuestionAnswerTaskProcessor paddedWorkerProcessor = new QuestionAnswerTaskProcessor(
			repository,
			orchestrator,
			" worker-1 ",
			Duration.ofMinutes(2),
			5
		);
		ClaimedQuestionTask canonicalClaim = claim(1);
		when(repository.claimByQuestionId(42L, " worker-1 ", Duration.ofMinutes(2), 5))
			.thenReturn(Optional.of(canonicalClaim));
		doThrow(new RuntimeException("provider failure"))
			.when(orchestrator).process(canonicalClaim);

		paddedWorkerProcessor.process(42L);

		verify(repository).markRetry(
			42L,
			"worker-1",
			canonicalClaim.leaseToken(),
			Duration.ofSeconds(10),
			QuestionTaskFailure.UNEXPECTED_TRANSIENT
		);
		verify(repository, never()).markRetry(
			42L,
			" worker-1 ",
			canonicalClaim.leaseToken(),
			Duration.ofSeconds(10),
			QuestionTaskFailure.UNEXPECTED_TRANSIENT
		);
	}

	private ClaimedQuestionTask claim(int attempts) {
		return new ClaimedQuestionTask(
			42L,
			"worker-1",
			UUID.randomUUID(),
			OffsetDateTime.now().plusMinutes(2),
			attempts
		);
	}

	private ListAppender<ILoggingEvent> captureLogs() {
		Logger logger = (Logger) LoggerFactory.getLogger(QuestionAnswerTaskProcessor.class);
		logger.detachAndStopAllAppenders();
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private java.util.List<String> messages(ListAppender<ILoggingEvent> appender) {
		return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	private void assertTransientClassification(RuntimeException exception, QuestionTaskFailure failure) {
		ClaimedQuestionTask claim = claim(1);
		when(repository.claimByQuestionId(42L, "worker-1", Duration.ofMinutes(2), 5))
			.thenReturn(Optional.of(claim));
		doThrow(exception).when(orchestrator).process(claim);

		processor.process(42L);

		verify(repository).markRetry(
			42L,
			"worker-1",
			claim.leaseToken(),
			Duration.ofSeconds(10),
			failure
		);
	}

	private void assertGenerationFailureClassification(
		LocalAnswerProviderFailureCode primaryFailure,
		LocalAnswerProviderFailureCode fallbackFailure,
		QuestionTaskFailure expectedFailure
	) {
		QuestionGenerationUnavailableException exception = new QuestionGenerationUnavailableException(
			primaryFailure,
			fallbackFailure
		);
		assertTransientClassification(exception, expectedFailure);
	}

	private void assertGroundingFailureClassification(
		LocalAnswerProviderFailureCode primaryFailure,
		LocalAnswerProviderFailureCode fallbackFailure,
		QuestionTaskFailure expectedFailure
	) {
		QuestionGroundingUnavailableException exception = new QuestionGroundingUnavailableException(
			primaryFailure,
			fallbackFailure
		);
		assertTransientClassification(exception, expectedFailure);
	}

	private void assertWebGroundingFailureClassification(
		RuntimeException exception,
		QuestionTaskFailure expectedFailure
	) {
		ClaimedQuestionTask claim = claim(1);
		when(repository.claimByQuestionId(42L, "worker-1", Duration.ofMinutes(2), 5))
			.thenReturn(Optional.of(claim));
		doThrow(exception).when(orchestrator).process(claim);

		processor.process(42L);

		if (expectedFailure.disposition() == QuestionTaskFailureDisposition.DEAD) {
			verify(repository).markDead(
				42L,
				"worker-1",
				claim.leaseToken(),
				expectedFailure
			);
			verify(repository, never()).markRetry(
				org.mockito.ArgumentMatchers.anyLong(),
				org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any()
			);
			return;
		}

		verify(repository).markRetry(
			42L,
			"worker-1",
			claim.leaseToken(),
			Duration.ofSeconds(10),
			expectedFailure
		);
		verify(repository, never()).markDead(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	private enum StaleFailure {
		CHECKPOINT {
			@Override
			RuntimeException exception() {
				return new StaleQuestionCheckpointException(42L);
			}
		},
		FINALIZATION {
			@Override
			RuntimeException exception() {
				return new StaleQuestionTaskFinalizationException(42L);
			}
		};

		abstract RuntimeException exception();
	}

	private static final class CyclicRuntimeException extends RuntimeException {

		@Override
		public synchronized Throwable getCause() {
			return this;
		}
	}
}
