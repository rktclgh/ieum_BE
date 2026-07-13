package shinhan.fibri.ieum.main.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.service.NotificationPublisher;

class AiQuestionAnswerCompletionServiceTest {

	private static final long QUESTION_ID = 10L;
	private static final long PIN_ID = 20L;
	private static final long USER_ID = 30L;
	private static final long ANSWER_ID = 40L;

	private final AiQuestionAnswerCompletionRepository repository =
		mock(AiQuestionAnswerCompletionRepository.class);
	private final NotificationPublisher publisher = mock(NotificationPublisher.class);
	private final AiQuestionAnswerCompletionService service =
		new AiQuestionAnswerCompletionService(repository, publisher);

	@Test
	void publishesAiAnswerNotificationThenAcknowledgesTheCompletedTicket() {
		givenCompletedTicket(false, false, null);
		when(publisher.publishDurableOnce(
			USER_ID,
			NotificationType.question,
			"새 답변",
			"회원님의 질문에 답변이 달렸어요",
			QUESTION_ID,
			true,
			"answer-created:" + ANSWER_ID
		)).thenReturn(true);
		when(repository.acknowledgeNotification(QUESTION_ID, ANSWER_ID)).thenReturn(1);

		service.complete(QUESTION_ID, ANSWER_ID);

		InOrder order = inOrder(repository, publisher);
		order.verify(repository).lockTicket(QUESTION_ID);
		order.verify(repository).lockQuestion(QUESTION_ID);
		order.verify(repository).lockPin(PIN_ID);
		order.verify(repository).isMatchingAiAnswer(QUESTION_ID, ANSWER_ID);
		order.verify(publisher).publishDurableOnce(
			USER_ID,
			NotificationType.question,
			"새 답변",
			"회원님의 질문에 답변이 달렸어요",
			QUESTION_ID,
			true,
			"answer-created:" + ANSWER_ID
		);
		order.verify(repository).acknowledgeNotification(QUESTION_ID, ANSWER_ID);
	}

	@Test
	void duplicateCallbackReturnsWithoutPublishingAgain() {
		givenCompletedTicket(false, false, OffsetDateTime.parse("2026-07-13T12:00:00+09:00"));

		service.complete(QUESTION_ID, ANSWER_ID);

		verify(publisher, never()).publishDurableOnce(
			USER_ID,
			NotificationType.question,
			"새 답변",
			"회원님의 질문에 답변이 달렸어요",
			QUESTION_ID,
			true,
			"answer-created:" + ANSWER_ID
		);
		verify(repository, never()).acknowledgeNotification(QUESTION_ID, ANSWER_ID);
	}

	@Test
	void deletedQuestionSuppressesNotificationButAcknowledgesCompletion() {
		givenCompletedTicket(true, false, null);
		when(repository.acknowledgeNotification(QUESTION_ID, ANSWER_ID)).thenReturn(1);

		service.complete(QUESTION_ID, ANSWER_ID);

		verify(publisher, never()).publishDurableOnce(
			USER_ID,
			NotificationType.question,
			"새 답변",
			"회원님의 질문에 답변이 달렸어요",
			QUESTION_ID,
			true,
			"answer-created:" + ANSWER_ID
		);
		verify(repository).acknowledgeNotification(QUESTION_ID, ANSWER_ID);
	}

	@Test
	void eventKeyConflictStillAcknowledgesCompletion() {
		givenCompletedTicket(false, false, null);
		when(publisher.publishDurableOnce(
			USER_ID,
			NotificationType.question,
			"새 답변",
			"회원님의 질문에 답변이 달렸어요",
			QUESTION_ID,
			true,
			"answer-created:" + ANSWER_ID
		)).thenReturn(false);
		when(repository.acknowledgeNotification(QUESTION_ID, ANSWER_ID)).thenReturn(1);

		service.complete(QUESTION_ID, ANSWER_ID);

		verify(repository).acknowledgeNotification(QUESTION_ID, ANSWER_ID);
	}

	@Test
	void missingTicketIsNotFound() {
		when(repository.lockTicket(QUESTION_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.complete(QUESTION_ID, ANSWER_ID))
			.isInstanceOf(AiQuestionAnswerTicketNotFoundException.class);

		verify(repository, never()).lockQuestion(QUESTION_ID);
	}

	@Test
	void incompleteOrMismatchedTicketIsConflict() {
		when(repository.lockTicket(QUESTION_ID)).thenReturn(Optional.of(
			new AiQuestionAnswerCompletionRepository.LockedTicket("processing", ANSWER_ID, null)
		));

		assertThatThrownBy(() -> service.complete(QUESTION_ID, ANSWER_ID))
			.isInstanceOf(AiQuestionAnswerCompletionConflictException.class);

		verify(repository, never()).lockQuestion(QUESTION_ID);
	}

	@Test
	void publisherFailureDoesNotAcknowledgeSoTheCallbackCanRetry() {
		givenCompletedTicket(false, false, null);
		when(publisher.publishDurableOnce(
			USER_ID,
			NotificationType.question,
			"새 답변",
			"회원님의 질문에 답변이 달렸어요",
			QUESTION_ID,
			true,
			"answer-created:" + ANSWER_ID
		)).thenThrow(new IllegalStateException("database failure"));

		assertThatThrownBy(() -> service.complete(QUESTION_ID, ANSWER_ID))
			.isInstanceOf(IllegalStateException.class);

		verify(repository, never()).acknowledgeNotification(QUESTION_ID, ANSWER_ID);
	}

	@Test
	void completionRunsInOneThirtySecondTransaction() throws NoSuchMethodException {
		Transactional transactional = AiQuestionAnswerCompletionService.class
			.getMethod("complete", Long.class, Long.class)
			.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.timeout()).isEqualTo(30);
	}

	private void givenCompletedTicket(
		boolean questionDeleted,
		boolean pinDeleted,
		OffsetDateTime processedAt
	) {
		when(repository.lockTicket(QUESTION_ID)).thenReturn(Optional.of(
			new AiQuestionAnswerCompletionRepository.LockedTicket("completed", ANSWER_ID, processedAt)
		));
		when(repository.lockQuestion(QUESTION_ID)).thenReturn(Optional.of(
			new AiQuestionAnswerCompletionRepository.LockedQuestion(PIN_ID, USER_ID, questionDeleted)
		));
		when(repository.lockPin(PIN_ID)).thenReturn(Optional.of(
			new AiQuestionAnswerCompletionRepository.LockedPin(pinDeleted)
		));
		when(repository.isMatchingAiAnswer(QUESTION_ID, ANSWER_ID)).thenReturn(true);
	}
}
