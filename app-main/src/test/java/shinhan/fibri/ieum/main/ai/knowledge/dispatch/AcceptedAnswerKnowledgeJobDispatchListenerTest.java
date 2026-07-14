package shinhan.fibri.ieum.main.ai.knowledge.dispatch;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import shinhan.fibri.ieum.main.answer.event.AcceptedHumanAnswerEvent;

class AcceptedAnswerKnowledgeJobDispatchListenerTest {

	@Test
	void dispatchesExactlyOnceOnlyAfterTheAcceptanceTransactionCommits() {
		try (AnnotationConfigApplicationContext context = context()) {
			AcceptedAnswerKnowledgeJobDispatchClient client =
				context.getBean(AcceptedAnswerKnowledgeJobDispatchClient.class);
			ApplicationEventPublisher events = context;
			TransactionTemplate transactions = new TransactionTemplate(
				context.getBean(PlatformTransactionManager.class)
			);

			transactions.executeWithoutResult(status -> {
				events.publishEvent(new AcceptedHumanAnswerEvent(42L));
				verify(client, never()).dispatch(42L);
			});

			verify(client).dispatch(42L);
		}
	}

	@Test
	void doesNotDispatchWhenTheAcceptanceTransactionRollsBack() {
		try (AnnotationConfigApplicationContext context = context()) {
			AcceptedAnswerKnowledgeJobDispatchClient client =
				context.getBean(AcceptedAnswerKnowledgeJobDispatchClient.class);
			TransactionTemplate transactions = new TransactionTemplate(
				context.getBean(PlatformTransactionManager.class)
			);

			transactions.executeWithoutResult(status -> {
				context.publishEvent(new AcceptedHumanAnswerEvent(42L));
				status.setRollbackOnly();
			});

			verify(client, never()).dispatch(42L);
		}
	}

	@Test
	void dispatchFailureAfterCommitDoesNotEscapeTheCommittedTransaction() {
		try (AnnotationConfigApplicationContext context = context()) {
			AcceptedAnswerKnowledgeJobDispatchClient client =
				context.getBean(AcceptedAnswerKnowledgeJobDispatchClient.class);
			doThrow(new IllegalStateException("app-ai unavailable")).when(client).dispatch(42L);
			TransactionTemplate transactions = new TransactionTemplate(
				context.getBean(PlatformTransactionManager.class)
			);

			assertThatCode(() -> transactions.executeWithoutResult(status ->
				context.publishEvent(new AcceptedHumanAnswerEvent(42L))
			)).doesNotThrowAnyException();

			verify(client).dispatch(42L);
		}
	}

	@Test
	void saturatedExecutorDropsTheDispatchWithoutCallerRuns() {
		AcceptedAnswerKnowledgeJobDispatchClient client = mock(AcceptedAnswerKnowledgeJobDispatchClient.class);
		Executor rejectingExecutor = task -> {
			throw new RejectedExecutionException("full");
		};
		AcceptedAnswerKnowledgeJobDispatchListener listener =
			new AcceptedAnswerKnowledgeJobDispatchListener(client, rejectingExecutor);

		assertThatCode(() -> listener.onAcceptedHumanAnswer(new AcceptedHumanAnswerEvent(42L)))
			.doesNotThrowAnyException();

		verify(client, never()).dispatch(42L);
	}

	private AnnotationConfigApplicationContext context() {
		return new AnnotationConfigApplicationContext(ListenerTestConfiguration.class);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement
	static class ListenerTestConfiguration {

		@Bean
		AcceptedAnswerKnowledgeJobDispatchClient acceptedAnswerKnowledgeJobDispatchClient() {
			return mock(AcceptedAnswerKnowledgeJobDispatchClient.class);
		}

		@Bean
		Executor acceptedAnswerKnowledgeDispatchTaskExecutor() {
			return Runnable::run;
		}

		@Bean
		AcceptedAnswerKnowledgeJobDispatchListener acceptedAnswerKnowledgeJobDispatchListener(
			AcceptedAnswerKnowledgeJobDispatchClient client,
			Executor acceptedAnswerKnowledgeDispatchTaskExecutor
		) {
			return new AcceptedAnswerKnowledgeJobDispatchListener(
				client,
				acceptedAnswerKnowledgeDispatchTaskExecutor
			);
		}

		@Bean
		PlatformTransactionManager transactionManager() {
			return new InMemoryTransactionManager();
		}
	}

	private static final class InMemoryTransactionManager extends AbstractPlatformTransactionManager {

		@Override
		protected Object doGetTransaction() {
			return new Object();
		}

		@Override
		protected void doBegin(Object transaction, TransactionDefinition definition) {
		}

		@Override
		protected void doCommit(DefaultTransactionStatus status) {
		}

		@Override
		protected void doRollback(DefaultTransactionStatus status) {
		}
	}
}
