package shinhan.fibri.ieum.ai.question.service;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import shinhan.fibri.ieum.ai.question.repository.ClaimedQuestionTask;
import shinhan.fibri.ieum.ai.question.repository.QuestionTaskWorkRepository;

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
	void retriesRuntimeFailuresWithTheCanonicalSafeErrorAndAttemptBackoff(
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
			Duration.ofSeconds(expectedBackoffSeconds)
		);
		verify(repository, never()).markDead(42L, "worker-1", claim.leaseToken());
	}

	@Test
	void marksTheCurrentFenceDeadWhenTheFinalAttemptFails() {
		ClaimedQuestionTask claim = claim(5);
		when(repository.claimByQuestionId(42L, "worker-1", Duration.ofMinutes(2), 5))
			.thenReturn(Optional.of(claim));
		doThrow(new RuntimeException("sensitive provider payload"))
			.when(orchestrator).process(claim);

		processor.process(42L);

		verify(repository).markDead(42L, "worker-1", claim.leaseToken());
		verify(repository, never()).markRetry(
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
			Duration.ofSeconds(10)
		);
		verify(repository, never()).markRetry(
			42L,
			" worker-1 ",
			canonicalClaim.leaseToken(),
			Duration.ofSeconds(10)
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
}
