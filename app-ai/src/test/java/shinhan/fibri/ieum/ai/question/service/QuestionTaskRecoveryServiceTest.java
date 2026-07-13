package shinhan.fibri.ieum.ai.question.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import shinhan.fibri.ieum.ai.question.repository.QuestionTaskWorkRepository;

class QuestionTaskRecoveryServiceTest {

	@Test
	void cleansCancelledTasksMarksExhaustedTasksDeadThenSubmitsBoundedDueIds() {
		QuestionTaskWorkRepository repository = mock(QuestionTaskWorkRepository.class);
		QuestionAnswerTaskLane lane = mock(QuestionAnswerTaskLane.class);
		when(lane.isEnabled()).thenReturn(true);
		when(repository.findDueQuestionIds(5, 32)).thenReturn(List.of(10L, 11L));
		when(lane.submit(10L)).thenReturn(QuestionAnswerTaskSubmission.ENQUEUED);
		when(lane.submit(11L)).thenReturn(QuestionAnswerTaskSubmission.ALREADY_ACTIVE);
		QuestionTaskRecoveryService recovery = new QuestionTaskRecoveryService(
			repository,
			lane,
			5,
			32
		);

		recovery.recover();

		InOrder order = inOrder(repository, lane);
		order.verify(lane).isEnabled();
		order.verify(repository).cleanupCancelledOrDeleted(32);
		order.verify(repository).markExhaustedDueTasksDead(5, 32);
		order.verify(repository).findDueQuestionIds(5, 32);
		order.verify(lane).submit(10L);
		order.verify(lane).submit(11L);
	}

	@Test
	void disabledLaneSkipsDatabaseRecovery() {
		QuestionTaskWorkRepository repository = mock(QuestionTaskWorkRepository.class);
		QuestionAnswerTaskLane lane = mock(QuestionAnswerTaskLane.class);
		when(lane.isEnabled()).thenReturn(false);
		QuestionTaskRecoveryService recovery = new QuestionTaskRecoveryService(
			repository,
			lane,
			5,
			32
		);

		recovery.recover();

		verifyNoInteractions(repository);
	}

	@Test
	void schedulerUsesApplicationReadyAndSixtySecondFixedDelay() throws Exception {
		Method startup = QuestionTaskRecoveryScheduler.class.getMethod("recoverAtStartup");
		Method scheduled = QuestionTaskRecoveryScheduler.class.getMethod("recoverPeriodically");

		assertThat(startup.getAnnotation(EventListener.class)).isNotNull();
		Scheduled schedule = scheduled.getAnnotation(Scheduled.class);
		assertThat(schedule.fixedDelayString()).isEqualTo("${app.ai.question-answer.recovery-interval:60s}");
		assertThat(schedule.initialDelayString()).isEqualTo("${app.ai.question-answer.recovery-interval:60s}");
	}
}
