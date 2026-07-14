package shinhan.fibri.ieum.ai.question.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class QuestionCompletionCallbackDeliveryServiceTest {

	private final QuestionCompletionCallbackRepository repository = mock(QuestionCompletionCallbackRepository.class);
	private final QuestionCompletionCallbackClient client = mock(QuestionCompletionCallbackClient.class);
	private final QuestionCompletionCallbackDeliveryService service =
		new QuestionCompletionCallbackDeliveryService(repository, client);

	@Test
	void loadsTheFreshDurableAnswerIdImmediatelyBeforeDelivery() {
		when(repository.findPending(42L)).thenReturn(Optional.of(new PendingQuestionCompletion(42L, 123L)));
		when(client.deliver(42L, 123L)).thenReturn(CallbackHttpResult.DELIVERED);

		assertThat(service.deliver(42L)).isEqualTo(CallbackDeliveryResult.DELIVERED);

		verify(repository).findPending(42L);
		verify(client).deliver(42L, 123L);
		verifyNoMoreInteractions(repository, client);
	}

	@Test
	void skipsCompletedInsufficientEvidenceWithoutAnAnswer() {
		when(repository.findPending(42L)).thenReturn(Optional.empty());

		assertThat(service.deliver(42L)).isEqualTo(CallbackDeliveryResult.NOT_PENDING);

		verify(repository).findPending(42L);
		verifyNoMoreInteractions(repository, client);
	}

	@Test
	void makesOnlyOneAttemptForANonTwoHundredResponse() {
		when(repository.findPending(42L)).thenReturn(Optional.of(new PendingQuestionCompletion(42L, 123L)));
		when(client.deliver(42L, 123L)).thenReturn(CallbackHttpResult.FAILED);

		assertThat(service.deliver(42L)).isEqualTo(CallbackDeliveryResult.FAILED);

		verify(repository).findPending(42L);
		verify(client).deliver(42L, 123L);
		verifyNoMoreInteractions(repository, client);
	}

	@Test
	void endsANotFoundDeliveryOnlyWhenTheTaskWasCascadeDeleted() {
		when(repository.findPending(42L)).thenReturn(Optional.of(new PendingQuestionCompletion(42L, 123L)));
		when(client.deliver(42L, 123L)).thenReturn(CallbackHttpResult.NOT_FOUND);
		when(repository.existsByQuestionId(42L)).thenReturn(false);

		assertThat(service.deliver(42L)).isEqualTo(CallbackDeliveryResult.NOT_FOUND_TASK_MISSING);

		verify(repository).findPending(42L);
		verify(client).deliver(42L, 123L);
		verify(repository).existsByQuestionId(42L);
		verifyNoMoreInteractions(repository, client);
	}

	@Test
	void leavesAnExistingNotFoundTaskEligibleForALaterExactDispatch() {
		when(repository.findPending(42L)).thenReturn(Optional.of(new PendingQuestionCompletion(42L, 123L)));
		when(client.deliver(42L, 123L)).thenReturn(CallbackHttpResult.NOT_FOUND);
		when(repository.existsByQuestionId(42L)).thenReturn(true);

		assertThat(service.deliver(42L)).isEqualTo(CallbackDeliveryResult.NOT_FOUND_TASK_EXISTS);

		verify(repository).findPending(42L);
		verify(client).deliver(42L, 123L);
		verify(repository).existsByQuestionId(42L);
		verifyNoMoreInteractions(repository, client);
	}
}
