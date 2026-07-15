package shinhan.fibri.ieum.main.admin.content.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.main.admin.content.exception.ContentNotFoundException;
import shinhan.fibri.ieum.main.admin.content.exception.UnsupportedContentTypeException;
import shinhan.fibri.ieum.main.question.exception.QuestionForbiddenException;
import shinhan.fibri.ieum.main.question.service.QuestionDeletionExecutor;

@Service
@RequiredArgsConstructor
public class AdminContentService {

	private static final String QUESTION_TYPE = "question";

	private final QuestionDeletionExecutor questionDeletionExecutor;

	@Transactional
	public void hide(String type, Long id) {
		if (!QUESTION_TYPE.equals(type)) {
			throw new UnsupportedContentTypeException(type);
		}
		hideQuestion(id);
	}

	private void hideQuestion(Long questionId) {
		questionDeletionExecutor.deleteQuestion(
			questionId,
			null,
			ContentNotFoundException::new,
			QuestionForbiddenException::new
		);
	}
}
