package shinhan.fibri.ieum.main.translation.service;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;
import shinhan.fibri.ieum.main.answer.domain.Answer;
import shinhan.fibri.ieum.main.answer.exception.AnswerNotFoundException;
import shinhan.fibri.ieum.main.answer.repository.AnswerRepository;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;
import shinhan.fibri.ieum.main.report.exception.ReportMessageNotFoundException;
import shinhan.fibri.ieum.main.translation.dto.TranslationResponse;

@Service
@RequiredArgsConstructor
public class TranslationService {

	private static final int MAX_TEXT_CODE_POINTS = 5_000;

	private final AnswerRepository answerRepository;
	private final QuestionRepository questionRepository;
	private final MessageRepository messageRepository;
	private final TranslationRateLimiter rateLimiter;
	private final TranslationCache translationCache;
	private final TranslationClient translationClient;

	@Transactional(readOnly = true)
	public TranslationResponse translateAnswer(
		AuthenticatedUser principal,
		Long answerId,
		TargetLanguage targetLanguage
	) {
		Long userId = requirePrincipal(principal).userId();
		TargetLanguage normalizedTarget = Objects.requireNonNull(targetLanguage, "targetLanguage must not be null");
		Answer answer = answerRepository.findById(answerId).orElseThrow(AnswerNotFoundException::new);
		questionRepository.findActiveByIdForTranslation(answer.getQuestionId())
			.orElseThrow(AnswerNotFoundException::new);
		return translateAuthorized(
			userId,
			TranslationSubjectKind.ANSWER,
			answerId,
			normalizedTarget,
			answer.getContent()
		);
	}

	@Transactional(readOnly = true)
	public TranslationResponse translateChatMessage(
		AuthenticatedUser principal,
		Long messageId,
		TargetLanguage targetLanguage
	) {
		Long userId = requirePrincipal(principal).userId();
		TargetLanguage normalizedTarget = Objects.requireNonNull(targetLanguage, "targetLanguage must not be null");
		Message message = messageRepository.findVisibleByIdForTranslation(messageId, userId)
			.orElseThrow(ReportMessageNotFoundException::new);
		return translateAuthorized(
			userId,
			TranslationSubjectKind.CHAT_MESSAGE,
			messageId,
			normalizedTarget,
			message.getContent()
		);
	}

	private TranslationResponse translateAuthorized(
		Long userId,
		TranslationSubjectKind kind,
		Long subjectId,
		TargetLanguage targetLanguage,
		String text
	) {
		if (!rateLimiter.tryAcquire(userId)) {
			throw new TranslationRateLimitedException();
		}
		String translationText = requireTranslatableText(text);
		return translationCache.get(kind, subjectId, targetLanguage)
			.orElseGet(() -> translateAndCache(kind, subjectId, targetLanguage, translationText));
	}

	private TranslationResponse translateAndCache(
		TranslationSubjectKind kind,
		Long subjectId,
		TargetLanguage targetLanguage,
		String text
	) {
		ProviderTranslationResult result = translationClient.translate(text, targetLanguage);
		TranslationResponse response = new TranslationResponse(result.translatedText(), result.sourceLang());
		translationCache.put(kind, subjectId, targetLanguage, response);
		return response;
	}

	private String requireTranslatableText(String text) {
		if (text == null || text.isBlank()) {
			throw new TranslationNotAvailableException();
		}
		if (text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS) {
			throw new TranslationNotAvailableException();
		}
		return text;
	}

	private AuthenticatedUser requirePrincipal(AuthenticatedUser principal) {
		return Objects.requireNonNull(principal, "principal must not be null");
	}
}
