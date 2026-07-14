package shinhan.fibri.ieum.main.ai.question.dispatch;

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
import shinhan.fibri.ieum.main.notification.presence.QuestionCreatedEvent;

class QuestionAnswerJobDispatchListenerTest {

	@Test
	void dispatchesExactlyOnceOnlyAfterTheQuestionTransactionCommits() {
		try (AnnotationConfigApplicationContext context = context()) {
			QuestionAnswerJobDispatchClient client = context.getBean(QuestionAnswerJobDispatchClient.class);
			ApplicationEventPublisher events = context;
			TransactionTemplate transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

			transactions.executeWithoutResult(status -> {
				events.publishEvent(event(42L));
				verify(client, never()).dispatch(42L);
			});

			verify(client).dispatch(42L);
		}
	}

	@Test
	void doesNotDispatchWhenTheQuestionTransactionRollsBack() {
		try (AnnotationConfigApplicationContext context = context()) {
			QuestionAnswerJobDispatchClient client = context.getBean(QuestionAnswerJobDispatchClient.class);
			TransactionTemplate transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

			transactions.executeWithoutResult(status -> {
				context.publishEvent(event(42L));
				status.setRollbackOnly();
			});

			verify(client, never()).dispatch(42L);
		}
	}

	@Test
	void clientFailureAfterCommitIsBestEffortAndDoesNotEscape() {
		QuestionAnswerJobDispatchClient client = mock(QuestionAnswerJobDispatchClient.class);
		doThrow(new IllegalStateException("app-ai unavailable")).when(client).dispatch(42L);
		QuestionAnswerJobDispatchListener listener = new QuestionAnswerJobDispatchListener(client, Runnable::run);

		assertThatCode(() -> listener.onQuestionCreated(event(42L))).doesNotThrowAnyException();

		verify(client).dispatch(42L);
	}

	@Test
	void saturatedExecutorDropsOnlyTheWakeWithoutCallerRuns() {
		QuestionAnswerJobDispatchClient client = mock(QuestionAnswerJobDispatchClient.class);
		Executor rejectingExecutor = task -> {
			throw new RejectedExecutionException("full");
		};
		QuestionAnswerJobDispatchListener listener = new QuestionAnswerJobDispatchListener(client, rejectingExecutor);

		assertThatCode(() -> listener.onQuestionCreated(event(42L))).doesNotThrowAnyException();

		verify(client, never()).dispatch(42L);
	}

	private AnnotationConfigApplicationContext context() {
		return new AnnotationConfigApplicationContext(ListenerTestConfiguration.class);
	}

	private QuestionCreatedEvent event(long questionId) {
		return new QuestionCreatedEvent(questionId, 7L, "title", 37.5, 127.0);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement
	static class ListenerTestConfiguration {

		@Bean
		QuestionAnswerJobDispatchClient questionAnswerJobDispatchClient() {
			return mock(QuestionAnswerJobDispatchClient.class);
		}

		@Bean
		Executor questionAnswerDispatchTaskExecutor() {
			return Runnable::run;
		}

		@Bean
		QuestionAnswerJobDispatchListener questionAnswerJobDispatchListener(
			QuestionAnswerJobDispatchClient client,
			Executor questionAnswerDispatchTaskExecutor
		) {
			return new QuestionAnswerJobDispatchListener(client, questionAnswerDispatchTaskExecutor);
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
