package shinhan.fibri.ieum.main.translation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;
import shinhan.fibri.ieum.main.answer.domain.Answer;
import shinhan.fibri.ieum.main.answer.exception.AnswerNotFoundException;
import shinhan.fibri.ieum.main.answer.repository.AnswerRepository;
import shinhan.fibri.ieum.main.question.domain.Question;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;
import shinhan.fibri.ieum.main.report.exception.ReportMessageNotFoundException;
import shinhan.fibri.ieum.main.translation.dto.TranslationResponse;

class TranslationServiceTest {

	private final AnswerRepository answerRepository = mock(AnswerRepository.class);
	private final QuestionRepository questionRepository = mock(QuestionRepository.class);
	private final MessageRepository messageRepository = mock(MessageRepository.class);
	private final TranslationRateLimiter rateLimiter = mock(TranslationRateLimiter.class);
	private final TranslationCache translationCache = mock(TranslationCache.class);
	private final TranslationClient translationClient = mock(TranslationClient.class);
	private final TranslationService service = new TranslationService(
		answerRepository,
		questionRepository,
		messageRepository,
		rateLimiter,
		translationCache,
		translationClient
	);

	@Test
	void answerCacheMissReadsServerTextCallsProviderAndStoresResponse() {
		Answer answer = answer(10L, 200L, "hello");
		when(answerRepository.findById(10L)).thenReturn(Optional.of(answer));
		when(questionRepository.findActiveByIdForTranslation(200L))
			.thenReturn(Optional.of(Question.create(5L, 42L, "title", "content")));
		when(rateLimiter.tryAcquire(42L)).thenReturn(true);
		when(translationCache.get(TranslationSubjectKind.ANSWER, 10L, TargetLanguage.KO)).thenReturn(Optional.empty());
		when(translationClient.translate("hello", TargetLanguage.KO))
			.thenReturn(new ProviderTranslationResult("안녕", "en"));

		TranslationResponse response = service.translateAnswer(principal(), 10L, TargetLanguage.KO);

		assertThat(response).isEqualTo(new TranslationResponse("안녕", "en"));
		verify(translationCache).put(TranslationSubjectKind.ANSWER, 10L, TargetLanguage.KO, response);
	}

	@Test
	void answerCacheHitAuthorizesBeforeReturningWithoutProviderCall() {
		Answer answer = answer(10L, 200L, "hello");
		TranslationResponse cached = new TranslationResponse("cached", "en");
		when(answerRepository.findById(10L)).thenReturn(Optional.of(answer));
		when(questionRepository.findActiveByIdForTranslation(200L))
			.thenReturn(Optional.of(Question.create(5L, 42L, "title", "content")));
		when(rateLimiter.tryAcquire(42L)).thenReturn(true);
		when(translationCache.get(TranslationSubjectKind.ANSWER, 10L, TargetLanguage.KO)).thenReturn(Optional.of(cached));

		TranslationResponse response = service.translateAnswer(principal(), 10L, TargetLanguage.KO);

		assertThat(response).isEqualTo(cached);
		InOrder inOrder = inOrder(answerRepository, questionRepository, rateLimiter, translationCache);
		inOrder.verify(answerRepository).findById(10L);
		inOrder.verify(questionRepository).findActiveByIdForTranslation(200L);
		inOrder.verify(rateLimiter).tryAcquire(42L);
		inOrder.verify(translationCache).get(TranslationSubjectKind.ANSWER, 10L, TargetLanguage.KO);
		verify(translationClient, never()).translate(any(), any());
	}

	@Test
	void missingOrDeletedQuestionMakesAnswerUnavailableBeforeCacheLookup() {
		Answer answer = answer(10L, 200L, "hello");
		when(answerRepository.findById(10L)).thenReturn(Optional.of(answer));
		when(questionRepository.findActiveByIdForTranslation(200L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.translateAnswer(principal(), 10L, TargetLanguage.KO))
			.isInstanceOf(AnswerNotFoundException.class);

		verify(translationCache, never()).get(any(), any(), any());
		verify(translationClient, never()).translate(any(), any());
	}

	@Test
	void deletedQuestionRejectsAnswerTranslationBeforeRateLimitOrCache() {
		Answer answer = answer(10L, 200L, "hello");
		when(answerRepository.findById(10L)).thenReturn(Optional.of(answer));
		when(questionRepository.findActiveByIdForTranslation(200L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.translateAnswer(principal(), 10L, TargetLanguage.KO))
			.isInstanceOf(AnswerNotFoundException.class);

		verify(rateLimiter, never()).tryAcquire(any());
		verify(translationCache, never()).get(any(), any(), any());
		verify(translationClient, never()).translate(any(), any());
	}

	@Test
	void chatVisibilityDenialHappensBeforeCacheLookup() {
		when(messageRepository.findVisibleByIdForTranslation(99L, 42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.translateChatMessage(principal(), 99L, TargetLanguage.EN))
			.isInstanceOf(ReportMessageNotFoundException.class);

		verify(translationCache, never()).get(any(), any(), any());
		verify(translationClient, never()).translate(any(), any());
	}

	@Test
	void chatCacheMissUsesServerSideMessageContent() {
		Message message = mock(Message.class);
		when(message.getContent()).thenReturn("こんにちは");
		when(messageRepository.findVisibleByIdForTranslation(99L, 42L)).thenReturn(Optional.of(message));
		when(rateLimiter.tryAcquire(42L)).thenReturn(true);
		when(translationCache.get(TranslationSubjectKind.CHAT_MESSAGE, 99L, TargetLanguage.EN)).thenReturn(Optional.empty());
		when(translationClient.translate("こんにちは", TargetLanguage.EN))
			.thenReturn(new ProviderTranslationResult("hello", "ja"));

		TranslationResponse response = service.translateChatMessage(principal(), 99L, TargetLanguage.EN);

		assertThat(response).isEqualTo(new TranslationResponse("hello", "ja"));
		verify(translationClient).translate("こんにちは", TargetLanguage.EN);
	}

	@Test
	void blankAnswerContentIsNotAvailable() {
		Answer answer = answer(10L, 200L, "   ");
		when(answerRepository.findById(10L)).thenReturn(Optional.of(answer));
		when(questionRepository.findActiveByIdForTranslation(200L))
			.thenReturn(Optional.of(Question.create(5L, 42L, "title", "content")));
		when(rateLimiter.tryAcquire(42L)).thenReturn(true);

		assertThatThrownBy(() -> service.translateAnswer(principal(), 10L, TargetLanguage.KO))
			.isInstanceOf(TranslationNotAvailableException.class);

		verify(translationClient, never()).translate(any(), any());
	}

	@Test
	void rateLimitedBeforeCacheOrProvider() {
		Answer answer = answer(10L, 200L, "hello");
		when(answerRepository.findById(10L)).thenReturn(Optional.of(answer));
		when(questionRepository.findActiveByIdForTranslation(200L))
			.thenReturn(Optional.of(Question.create(5L, 42L, "title", "content")));
		when(rateLimiter.tryAcquire(42L)).thenReturn(false);

		assertThatThrownBy(() -> service.translateAnswer(principal(), 10L, TargetLanguage.KO))
			.isInstanceOf(TranslationRateLimitedException.class);

		verify(translationCache, never()).get(any(), any(), any());
		verify(translationClient, never()).translate(any(), any());
	}

	@Test
	void providerUnavailableMapsToTranslationUnavailable() {
		Answer answer = answer(10L, 200L, "hello");
		when(answerRepository.findById(10L)).thenReturn(Optional.of(answer));
		when(questionRepository.findActiveByIdForTranslation(200L))
			.thenReturn(Optional.of(Question.create(5L, 42L, "title", "content")));
		when(rateLimiter.tryAcquire(42L)).thenReturn(true);
		when(translationCache.get(TranslationSubjectKind.ANSWER, 10L, TargetLanguage.KO)).thenReturn(Optional.empty());
		when(translationClient.translate("hello", TargetLanguage.KO))
			.thenThrow(new TranslationProviderUnavailableException());

		assertThatThrownBy(() -> service.translateAnswer(principal(), 10L, TargetLanguage.KO))
			.isInstanceOf(TranslationProviderUnavailableException.class);

		verify(translationCache, never()).put(eq(TranslationSubjectKind.ANSWER), eq(10L), eq(TargetLanguage.KO), any());
	}

	private static Answer answer(Long id, Long questionId, String content) {
		Answer answer = mock(Answer.class);
		when(answer.getId()).thenReturn(id);
		when(answer.getQuestionId()).thenReturn(questionId);
		when(answer.getContent()).thenReturn(content);
		return answer;
	}

	private static AuthenticatedUser principal() {
		return new AuthenticatedUser(42L, "me@example.com", UserRole.user, UserStatus.active);
	}
}
