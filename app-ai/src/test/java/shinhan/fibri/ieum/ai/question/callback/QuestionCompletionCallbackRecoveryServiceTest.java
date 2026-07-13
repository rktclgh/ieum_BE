package shinhan.fibri.ieum.ai.question.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import shinhan.fibri.ieum.ai.question.service.QuestionCompletionCallbackWake;

class QuestionCompletionCallbackRecoveryServiceTest {

	@Test
	void submitsAtMostThirtyTwoDurableAckPendingQuestionIdsInDatabaseOrder() {
		QuestionCompletionCallbackRepository repository = mock(QuestionCompletionCallbackRepository.class);
		QuestionCompletionCallbackWake wake = mock(QuestionCompletionCallbackWake.class);
		when(repository.findPendingQuestionIds(32)).thenReturn(List.of(4L, 7L, 9L));
		QuestionCompletionCallbackRecoveryService service =
			new QuestionCompletionCallbackRecoveryService(repository, wake);

		service.recover();

		verify(repository).findPendingQuestionIds(32);
		verify(wake).wake(4L);
		verify(wake).wake(7L);
		verify(wake).wake(9L);
	}

	@Test
	void schedulerRunsAtStartupAndEveryConfiguredSixtySeconds() throws Exception {
		Method startup = QuestionCompletionCallbackRecoveryScheduler.class.getMethod("recoverAtStartup");
		Method scheduled = QuestionCompletionCallbackRecoveryScheduler.class.getMethod("recoverPeriodically");

		EventListener eventListener = startup.getAnnotation(EventListener.class);
		Scheduled schedule = scheduled.getAnnotation(Scheduled.class);

		assertThat(eventListener).isNotNull();
		assertThat(eventListener.value()).containsExactly(ApplicationReadyEvent.class);
		assertThat(schedule).isNotNull();
		assertThat(schedule.fixedDelayString())
			.isEqualTo("#{@questionCompletionCallbackProperties.recoveryInterval().toMillis()}");
		assertThat(schedule.initialDelayString())
			.isEqualTo("#{@questionCompletionCallbackProperties.recoveryInterval().toMillis()}");
	}
}
