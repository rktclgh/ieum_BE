package shinhan.fibri.ieum.main.notification.internal;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface AiQuestionAnswerCompletionRepository {

	Optional<LockedTicket> lockTicket(Long questionId);

	Optional<LockedQuestion> lockQuestion(Long questionId);

	Optional<LockedPin> lockPin(Long pinId);

	boolean isMatchingAiAnswer(Long questionId, Long answerId);

	int acknowledgeNotification(Long questionId, Long answerId);

	record LockedTicket(String status, Long answerId, OffsetDateTime notificationProcessedAt) {
	}

	record LockedQuestion(Long pinId, Long recipientUserId, boolean deleted) {
	}

	record LockedPin(boolean deleted) {
	}
}
