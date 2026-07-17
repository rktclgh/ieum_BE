package shinhan.fibri.ieum.main.notification.internal;

import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.service.NotificationPublisher;

@Service
public class AiQuestionAnswerCompletionService {

	private static final String COMPLETED = "completed";
	static final String AI_ANSWER_EVENT_KEY_PREFIX = "ai-answer-created:question:";

	private final AiQuestionAnswerCompletionRepository repository;
	private final NotificationPublisher publisher;

	public AiQuestionAnswerCompletionService(
		AiQuestionAnswerCompletionRepository repository,
		NotificationPublisher publisher
	) {
		this.repository = repository;
		this.publisher = publisher;
	}

	@Transactional(timeout = 30)
	public void complete(Long questionId, Long answerId) {
		Objects.requireNonNull(questionId, "questionId must not be null");
		Objects.requireNonNull(answerId, "answerId must not be null");

		AiQuestionAnswerCompletionRepository.LockedTicket ticket = repository.lockTicket(questionId)
			.orElseThrow(AiQuestionAnswerTicketNotFoundException::new);
		if (!COMPLETED.equals(ticket.status()) || !answerId.equals(ticket.answerId())) {
			throw new AiQuestionAnswerCompletionConflictException();
		}

		AiQuestionAnswerCompletionRepository.LockedQuestion question = repository.lockQuestion(questionId)
			.orElseThrow(AiQuestionAnswerCompletionConflictException::new);
		AiQuestionAnswerCompletionRepository.LockedPin pin = repository.lockPin(question.pinId())
			.orElseThrow(AiQuestionAnswerCompletionConflictException::new);
		if (!repository.isMatchingAiAnswer(questionId, answerId)) {
			throw new AiQuestionAnswerCompletionConflictException();
		}
		if (ticket.notificationProcessedAt() != null) {
			return;
		}

		if (!question.deleted() && !pin.deleted()) {
			publisher.publishDurableOnce(
				question.recipientUserId(),
				NotificationType.question,
				"새 답변",
				"회원님의 질문에 답변이 달렸어요",
				questionId,
				true,
				AI_ANSWER_EVENT_KEY_PREFIX + questionId
			);
		}

		if (repository.acknowledgeNotification(questionId, answerId) != 1) {
			throw new AiQuestionAnswerCompletionConflictException();
		}
	}
}
