package shinhan.fibri.ieum.main.question.service;

import java.time.OffsetDateTime;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.main.ai.question.repository.QuestionAnswerTicketWriter;
import shinhan.fibri.ieum.main.pin.repository.PinWriter;
import shinhan.fibri.ieum.main.question.domain.Question;
import shinhan.fibri.ieum.main.question.repository.QuestionDeletionState;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;

@Component
@RequiredArgsConstructor
public class QuestionDeletionExecutor {

	private final QuestionRepository questionRepository;
	private final PinWriter pinWriter;
	private final QuestionAnswerTicketWriter questionAnswerTicketWriter;

	public void deleteQuestion(
		Long questionId,
		Long requiredAuthorId,
		Supplier<? extends RuntimeException> notFoundException,
		Supplier<? extends RuntimeException> forbiddenException
	) {
		QuestionDeletionState precheck = questionRepository.findDeletionState(questionId)
			.orElseThrow(notFoundException);
		verifyAuthor(requiredAuthorId, precheck.getAuthorId(), forbiddenException);
		if (precheck.getDeletedAt() != null) {
			return;
		}

		questionAnswerTicketWriter.requestCancellation(questionId);
		Question question = questionRepository.findByIdForUpdate(questionId).orElse(null);
		if (question == null) {
			return;
		}
		verifyAuthor(requiredAuthorId, question.getAuthorId(), forbiddenException);
		if (question.isDeleted()) {
			return;
		}

		OffsetDateTime deletedAt = OffsetDateTime.now();
		question.softDelete(deletedAt);
		pinWriter.softDelete(question.getPinId(), deletedAt);
	}

	private void verifyAuthor(
		Long requiredAuthorId,
		Long actualAuthorId,
		Supplier<? extends RuntimeException> forbiddenException
	) {
		if (requiredAuthorId != null && !requiredAuthorId.equals(actualAuthorId)) {
			throw forbiddenException.get();
		}
	}
}
