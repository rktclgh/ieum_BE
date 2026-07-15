package shinhan.fibri.ieum.main.admin.content.service;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.main.admin.content.exception.ContentNotFoundException;
import shinhan.fibri.ieum.main.admin.content.exception.UnsupportedContentTypeException;
import shinhan.fibri.ieum.main.ai.question.repository.QuestionAnswerTicketWriter;
import shinhan.fibri.ieum.main.pin.repository.PinWriter;
import shinhan.fibri.ieum.main.question.domain.Question;
import shinhan.fibri.ieum.main.question.repository.QuestionDeletionState;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;

@Service
@RequiredArgsConstructor
public class AdminContentService {

	private static final String QUESTION_TYPE = "question";

	private final QuestionRepository questionRepository;
	private final PinWriter pinWriter;
	private final QuestionAnswerTicketWriter questionAnswerTicketWriter;

	@Transactional
	public void hide(String type, Long id) {
		if (!QUESTION_TYPE.equals(type)) {
			throw new UnsupportedContentTypeException(type);
		}
		hideQuestion(id);
	}

	private void hideQuestion(Long questionId) {
		QuestionDeletionState precheck = questionRepository.findDeletionState(questionId)
			.orElseThrow(ContentNotFoundException::new);
		if (precheck.getDeletedAt() != null) {
			return;
		}

		questionAnswerTicketWriter.requestCancellation(questionId);
		Question question = questionRepository.findByIdForUpdate(questionId).orElse(null);
		if (question == null || question.isDeleted()) {
			return;
		}

		OffsetDateTime deletedAt = OffsetDateTime.now();
		question.softDelete(deletedAt);
		pinWriter.softDelete(question.getPinId(), deletedAt);
	}
}
