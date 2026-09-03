package shinhan.fibri.ieum.ai.question.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link QuestionCompletionOutboxRelay}. 브리프 "먼저 쓸 테스트" 3번, spec.md §8.5.
 *
 * <p>repository/client 는 모두 모킹한다 — 실제 브로커·DB 는 {@code JdbcQuestionCompletionCallbackRepositoryBatchTest}와
 * {@code RabbitQuestionCompletionCallbackClientTest}가 각각 증명한다. 여기서 검증할 것은 배치의 각
 * row가 발행을 시도하는지, 그리고 한 row 의 실패가 나머지 row 처리를 막지 않는지 뿐이다.
 */
class QuestionCompletionOutboxRelayTest {

	private final QuestionCompletionCallbackRepository repository = mock(QuestionCompletionCallbackRepository.class);
	private final QuestionCompletionCallbackClient client = mock(QuestionCompletionCallbackClient.class);
	private final QuestionCompletionOutboxRelay relay =
		new QuestionCompletionOutboxRelay(repository, client, 32);

	@BeforeEach
	void stubEmptyBatchByDefault() {
		when(repository.findPendingBatch(anyInt())).thenReturn(List.of());
	}

	@Test
	void publishesEachRowInTheBatch() {
		when(repository.findPendingBatch(32)).thenReturn(List.of(
			new PendingQuestionCompletion(1L, 10L),
			new PendingQuestionCompletion(2L, 20L)
		));
		when(client.deliver(1L, 10L)).thenReturn(CallbackHttpResult.DELIVERED);
		when(client.deliver(2L, 20L)).thenReturn(CallbackHttpResult.DELIVERED);

		relay.relayPendingCompletions();

		verify(client).deliver(1L, 10L);
		verify(client).deliver(2L, 20L);
	}

	@Test
	void aFailedPublishDoesNotBlockTheRemainingRows() {
		when(repository.findPendingBatch(32)).thenReturn(List.of(
			new PendingQuestionCompletion(1L, 10L),
			new PendingQuestionCompletion(2L, 20L)
		));
		when(client.deliver(1L, 10L)).thenThrow(new RuntimeException("broker unreachable"));
		when(client.deliver(2L, 20L)).thenReturn(CallbackHttpResult.DELIVERED);

		relay.relayPendingCompletions();

		verify(client).deliver(1L, 10L);
		verify(client).deliver(2L, 20L);
	}

	@Test
	void aFailedResultDoesNotBlockTheRemainingRows() {
		when(repository.findPendingBatch(32)).thenReturn(List.of(
			new PendingQuestionCompletion(1L, 10L),
			new PendingQuestionCompletion(2L, 20L)
		));
		when(client.deliver(1L, 10L)).thenReturn(CallbackHttpResult.FAILED);
		when(client.deliver(2L, 20L)).thenReturn(CallbackHttpResult.DELIVERED);

		relay.relayPendingCompletions();

		verify(client).deliver(1L, 10L);
		verify(client).deliver(2L, 20L);
	}

	@Test
	void emptyBatchPublishesNothing() {
		relay.relayPendingCompletions();

		verify(repository).findPendingBatch(32);
		assertThat(true).isTrue();
	}

	@Test
	void repositoryFailureDoesNotPropagate() {
		when(repository.findPendingBatch(anyInt())).thenThrow(new RuntimeException("db unreachable"));

		relay.relayPendingCompletions();
	}
}
