package shinhan.fibri.ieum.main.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class WebPushSubscriptionCleanupTest {

	private final WebPushSubscriptionRepository repository = mock(WebPushSubscriptionRepository.class);
	private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();
	private final WebPushSubscriptionCleanup cleanup = new WebPushSubscriptionCleanup(repository, transactionManager);

	@Test
	void deleteForSessionRunsInRequiresNewAndDelegatesToRepository() {
		when(repository.deleteAllBySessionId("session-42")).thenReturn(1);

		cleanup.deleteForSession("session-42");

		verify(repository).deleteAllBySessionId("session-42");
		assertThat(transactionManager.propagationBehavior)
			.isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		assertThat(transactionManager.committed).isTrue();
	}

	@Test
	void deleteForUserRunsInRequiresNewAndDelegatesToRepository() {
		when(repository.deleteAllByUserId(42L)).thenReturn(2);

		cleanup.deleteForUser(42L);

		verify(repository).deleteAllByUserId(42L);
		assertThat(transactionManager.propagationBehavior)
			.isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Test
	void deleteForSessionSwallowsRepositoryFailure() {
		doThrow(new IllegalStateException("secret database detail"))
			.when(repository).deleteAllBySessionId("session-42");

		assertThatCode(() -> cleanup.deleteForSession("session-42"))
			.doesNotThrowAnyException();

		assertThat(transactionManager.rolledBack).isTrue();
	}

	@Test
	void deleteForUserSwallowsCommitFailure() {
		transactionManager.failCommit = true;

		assertThatCode(() -> cleanup.deleteForUser(42L))
			.doesNotThrowAnyException();
	}

	private static final class RecordingTransactionManager implements PlatformTransactionManager {

		private int propagationBehavior = Integer.MIN_VALUE;
		private boolean committed;
		private boolean rolledBack;
		private boolean failCommit;

		@Override
		public TransactionStatus getTransaction(TransactionDefinition definition) {
			propagationBehavior = definition.getPropagationBehavior();
			return new SimpleTransactionStatus(true);
		}

		@Override
		public void commit(TransactionStatus status) {
			committed = true;
			if (failCommit) {
				throw new IllegalStateException("secret commit detail");
			}
		}

		@Override
		public void rollback(TransactionStatus status) {
			rolledBack = true;
		}
	}
}
