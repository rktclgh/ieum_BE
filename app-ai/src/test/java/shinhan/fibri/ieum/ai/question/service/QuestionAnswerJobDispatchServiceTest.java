package shinhan.fibri.ieum.ai.question.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.question.repository.QuestionTaskDispatchSnapshot;
import shinhan.fibri.ieum.ai.question.repository.QuestionTaskStatus;
import shinhan.fibri.ieum.ai.question.repository.QuestionTaskWorkRepository;

class QuestionAnswerJobDispatchServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-13T00:00:00Z");

	private final QuestionTaskWorkRepository repository = mock(QuestionTaskWorkRepository.class);
	private final QuestionAnswerTaskLane lane = mock(QuestionAnswerTaskLane.class);
	private final QuestionCompletionCallbackWake callbackWake = mock(QuestionCompletionCallbackWake.class);
	private final QuestionAnswerJobDispatchService service = new QuestionAnswerJobDispatchService(
		repository,
		lane,
		callbackWake
	);

	@Test
	void returnsInvariantBreachWithoutCreatingAMissingTicket() {
		when(repository.findDispatchSnapshot(42L)).thenReturn(Optional.empty());

		assertThat(service.dispatch(42L)).isEqualTo(QuestionAnswerJobDispatchResult.INVARIANT_BREACH);

		verifyNoInteractions(lane, callbackWake);
	}

	@Test
	void returnsGoneForCancelledOrDeletedTickets() {
		when(repository.findDispatchSnapshot(42L)).thenReturn(Optional.of(snapshot(
			QuestionTaskStatus.PENDING, false, true, false, false, null, null
		)));

		assertThat(service.dispatch(42L)).isEqualTo(QuestionAnswerJobDispatchResult.CANCELLED_OR_DELETED);

		verifyNoInteractions(lane, callbackWake);
	}

	@Test
	void returnsConflictForDeadTickets() {
		when(repository.findDispatchSnapshot(42L)).thenReturn(Optional.of(snapshot(
			QuestionTaskStatus.DEAD, false, false, false, false, null, null
		)));

		assertThat(service.dispatch(42L)).isEqualTo(QuestionAnswerJobDispatchResult.DEAD);

		verifyNoInteractions(lane, callbackWake);
	}

	@Test
	void completedTicketWakesPendingCallbackDeliveryAndReturnsOk() {
		when(repository.findDispatchSnapshot(42L)).thenReturn(Optional.of(snapshot(
			QuestionTaskStatus.COMPLETED, false, false, false, false, 99L, null
		)));

		assertThat(service.dispatch(42L)).isEqualTo(QuestionAnswerJobDispatchResult.ALREADY_COMPLETED);

		verify(callbackWake).wake(42L);
		verifyNoInteractions(lane);
	}

	@Test
	void completedTicketDoesNotWakeCallbackWhenThereIsNoPendingAnswerAck() {
		when(repository.findDispatchSnapshot(42L)).thenReturn(Optional.of(snapshot(
			QuestionTaskStatus.COMPLETED,
			false,
			false,
			false,
			false,
			null,
			OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
		)));

		assertThat(service.dispatch(42L)).isEqualTo(QuestionAnswerJobDispatchResult.ALREADY_COMPLETED);

		verifyNoInteractions(lane, callbackWake);
	}

	@Test
	void completedDeletedTicketStillWakesTheAckOnlyCallbackPath() {
		when(repository.findDispatchSnapshot(42L)).thenReturn(Optional.of(snapshot(
			QuestionTaskStatus.COMPLETED, false, true, true, true, 99L, null
		)));

		assertThat(service.dispatch(42L)).isEqualTo(QuestionAnswerJobDispatchResult.ALREADY_COMPLETED);

		verify(callbackWake).wake(42L);
		verifyNoInteractions(lane);
	}

	@Test
	void activeProcessingLeaseIsAlreadyActiveWithoutAnotherQueueSubmission() {
		when(repository.findDispatchSnapshot(42L)).thenReturn(Optional.of(snapshot(
			QuestionTaskStatus.PROCESSING,
			true,
			false,
			false,
			false,
			null,
			null
		)));

		assertThat(service.dispatch(42L)).isEqualTo(QuestionAnswerJobDispatchResult.ALREADY_ACTIVE);

		verifyNoInteractions(lane, callbackWake);
	}

	@Test
	void mapsLaneSubmissionResultsForRunnableAndExpiredTasks() {
		when(repository.findDispatchSnapshot(42L)).thenReturn(Optional.of(snapshot(
			QuestionTaskStatus.PENDING, false, false, false, false, null, null
		)));
		when(lane.submit(42L)).thenReturn(QuestionAnswerTaskSubmission.ENQUEUED);
		assertThat(service.dispatch(42L)).isEqualTo(QuestionAnswerJobDispatchResult.ENQUEUED);

		when(repository.findDispatchSnapshot(43L)).thenReturn(Optional.of(snapshot(
			QuestionTaskStatus.RETRY, false, false, false, false, null, null
		)));
		when(lane.submit(43L)).thenReturn(QuestionAnswerTaskSubmission.ALREADY_ACTIVE);
		assertThat(service.dispatch(43L)).isEqualTo(QuestionAnswerJobDispatchResult.ALREADY_ACTIVE);

		when(repository.findDispatchSnapshot(44L)).thenReturn(Optional.of(snapshot(
			QuestionTaskStatus.PROCESSING,
			false,
			false,
			false,
			false,
			null,
			null
		)));
		when(lane.submit(44L)).thenReturn(QuestionAnswerTaskSubmission.SATURATED);
		assertThat(service.dispatch(44L)).isEqualTo(QuestionAnswerJobDispatchResult.SATURATED);

		when(repository.findDispatchSnapshot(45L)).thenReturn(Optional.of(snapshot(
			QuestionTaskStatus.PENDING, false, false, false, false, null, null
		)));
		when(lane.submit(45L)).thenReturn(QuestionAnswerTaskSubmission.DISABLED);
		assertThat(service.dispatch(45L)).isEqualTo(QuestionAnswerJobDispatchResult.DISABLED);
	}

	@Test
	void rejectsInvalidQuestionIdBeforeRepositoryAccess() {
		assertThat(service.dispatch(0L)).isEqualTo(QuestionAnswerJobDispatchResult.INVARIANT_BREACH);

		verify(repository, never()).findDispatchSnapshot(0L);
		verifyNoInteractions(lane, callbackWake);
	}

	private QuestionTaskDispatchSnapshot snapshot(
		QuestionTaskStatus status,
		boolean activeLease,
		boolean cancellationRequested,
		boolean questionDeleted,
		boolean pinDeleted,
		Long answerId,
		OffsetDateTime answerNotificationProcessedAt
	) {
		return new QuestionTaskDispatchSnapshot(
			42L,
			status,
			activeLease,
			cancellationRequested,
			questionDeleted,
			pinDeleted,
			answerId,
			answerNotificationProcessedAt
		);
	}
}
