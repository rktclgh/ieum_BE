package shinhan.fibri.ieum.main.answer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.file.domain.File;
import shinhan.fibri.ieum.common.file.repository.FileRepository;
import shinhan.fibri.ieum.main.answer.domain.Answer;
import shinhan.fibri.ieum.main.answer.domain.AnswerImage;
import shinhan.fibri.ieum.main.answer.dto.CreateAnswerRequest;
import shinhan.fibri.ieum.main.answer.dto.FinalizeAcceptedAnswersRequest;
import shinhan.fibri.ieum.main.answer.dto.FinalizeAcceptedAnswersResponse;
import shinhan.fibri.ieum.main.answer.exception.AnswerSelectionFinalizedException;
import shinhan.fibri.ieum.main.answer.exception.AnswerNotFoundException;
import shinhan.fibri.ieum.main.answer.exception.InvalidAnswerRequestException;
import shinhan.fibri.ieum.main.answer.exception.SelfAcceptanceNotAllowedException;
import shinhan.fibri.ieum.main.answer.event.AcceptedHumanAnswerEvent;
import shinhan.fibri.ieum.main.answer.repository.AnswerImageRepository;
import shinhan.fibri.ieum.main.answer.repository.AnswerRepository;
import shinhan.fibri.ieum.main.question.domain.Question;
import shinhan.fibri.ieum.main.question.exception.QuestionForbiddenException;
import shinhan.fibri.ieum.main.question.exception.QuestionNotFoundException;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.message.NotificationMessage;
import shinhan.fibri.ieum.main.notification.message.NotificationMessageKey;
import shinhan.fibri.ieum.main.notification.service.NotificationPublisher;

class AnswerServiceTest {

	private final QuestionRepository questionRepository = mock(QuestionRepository.class);
	private final AnswerRepository answerRepository = mock(AnswerRepository.class);
	private final AnswerImageRepository answerImageRepository = mock(AnswerImageRepository.class);
	private final FileRepository fileRepository = mock(FileRepository.class);
	private final UserRepository userRepository = mock(UserRepository.class);
	private final NotificationPublisher notificationPublisher = mock(NotificationPublisher.class);
	private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
	private final AnswerService service = new AnswerService(
		questionRepository,
		answerRepository,
		answerImageRepository,
		fileRepository,
		userRepository,
		notificationPublisher,
		eventPublisher
	);

	@Test
	void createPublishesDurableNotificationToQuestionAuthorButSkipsSelfAnswer() {
		Question question = Question.create(100L, 99L, "title", "question");
		setId(question, 200L);
		when(questionRepository.findActiveByIdForShare(200L)).thenReturn(Optional.of(question));
		when(answerRepository.save(any(Answer.class))).thenAnswer(invocation -> {
			Answer answer = invocation.getArgument(0);
			setId(answer, 300L);
			return answer;
		});

		service.create(principal(), 200L, new CreateAnswerRequest("answer", List.of()));

		verify(notificationPublisher).publishDurable(
			99L,
			NotificationType.question,
			NotificationMessage.of(NotificationMessageKey.ANSWER_CREATED),
			200L,
			false
		);
	}

	@Test
	void createSkipsNotificationWhenQuestionAuthorAnswersOwnQuestion() {
		Question question = Question.create(100L, 42L, "title", "question");
		setId(question, 200L);
		when(questionRepository.findActiveByIdForShare(200L)).thenReturn(Optional.of(question));
		when(answerRepository.save(any(Answer.class))).thenAnswer(invocation -> {
			Answer answer = invocation.getArgument(0);
			setId(answer, 300L);
			return answer;
		});

		service.create(principal(), 200L, new CreateAnswerRequest("answer", List.of()));

		verify(notificationPublisher, never()).publishDurable(any(), any(), any(), any(), any());
	}

	@Test
	void createValidatesQuestionAndImagesThenSavesAnswerAndImageLinksInOrder() {
		UUID firstImageId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID secondImageId = UUID.fromString("00000000-0000-0000-0000-000000000002");
		Question question = Question.create(100L, 99L, "title", "question");
		setId(question, 200L);
		when(questionRepository.findActiveByIdForShare(200L)).thenReturn(Optional.of(question));
		when(fileRepository.findAllByFileIdInAndUploaderId(List.of(firstImageId, secondImageId), 42L))
			.thenReturn(List.of(uploadedFile(firstImageId, 42L), uploadedFile(secondImageId, 42L)));
		when(answerRepository.save(any(Answer.class))).thenAnswer(invocation -> {
			Answer answer = invocation.getArgument(0);
			setId(answer, 300L);
			return answer;
		});

		var response = service.create(
			principal(),
			200L,
			new CreateAnswerRequest("answer body", List.of(firstImageId, secondImageId))
		);

		assertThat(response.answerId()).isEqualTo(300L);
		ArgumentCaptor<Answer> answerCaptor = ArgumentCaptor.forClass(Answer.class);
		verify(answerRepository).save(answerCaptor.capture());
		assertThat(answerCaptor.getValue().getQuestionId()).isEqualTo(200L);
		assertThat(answerCaptor.getValue().getAuthorId()).isEqualTo(42L);
		assertThat(answerCaptor.getValue().isAi()).isFalse();
		assertThat(answerCaptor.getValue().getContent()).isEqualTo("answer body");

		ArgumentCaptor<Iterable<AnswerImage>> imagesCaptor = ArgumentCaptor.forClass(Iterable.class);
		verify(answerImageRepository).saveAll(imagesCaptor.capture());
		List<AnswerImage> images = StreamSupport.stream(imagesCaptor.getValue().spliterator(), false)
			.toList();
		assertThat(images)
			.extracting(AnswerImage::getAnswerId, AnswerImage::getFileId, AnswerImage::getSortOrder)
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple(300L, firstImageId, 0),
				org.assertj.core.groups.Tuple.tuple(300L, secondImageId, 1)
			);

		InOrder inOrder = inOrder(questionRepository, fileRepository, answerRepository, answerImageRepository);
		inOrder.verify(questionRepository).findActiveByIdForShare(200L);
		inOrder.verify(fileRepository, times(1)).findAllByFileIdInAndUploaderId(List.of(firstImageId, secondImageId), 42L);
		inOrder.verify(answerRepository).save(any(Answer.class));
		inOrder.verify(answerImageRepository).saveAll(any());
		verify(fileRepository, never()).findByFileIdAndUploaderId(any(), any());
	}

	@Test
	void createRejectsMissingQuestionBeforeValidatingImages() {
		UUID imageId = UUID.fromString("00000000-0000-0000-0000-000000000003");
		when(questionRepository.findActiveByIdForShare(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.create(
			principal(),
			999L,
			new CreateAnswerRequest("answer body", List.of(imageId))
		)).isInstanceOf(QuestionNotFoundException.class);

		verify(fileRepository, never()).findAllByFileIdInAndUploaderId(any(), any());
		verify(answerRepository, never()).save(any());
	}

	@Test
	void createRejectsResolvedQuestionBeforeContentAndImageValidationOrSideEffects() {
		UUID imageId = UUID.fromString("00000000-0000-0000-0000-000000000007");
		Question question = Question.create(100L, 99L, "title", "question");
		setId(question, 200L);
		question.markResolved();
		when(questionRepository.findActiveByIdForShare(200L)).thenReturn(Optional.of(question));

		assertThatThrownBy(() -> service.create(
			principal(),
			200L,
			new CreateAnswerRequest("   ", List.of(imageId, imageId))
		)).isInstanceOf(AnswerSelectionFinalizedException.class);

		InOrder inOrder = inOrder(questionRepository, fileRepository, answerRepository, answerImageRepository, notificationPublisher);
		inOrder.verify(questionRepository).findActiveByIdForShare(200L);
		verify(fileRepository, never()).findAllByFileIdInAndUploaderId(any(), any());
		verify(answerRepository, never()).save(any());
		verify(answerImageRepository, never()).saveAll(any());
		verify(notificationPublisher, never()).publishDurable(any(), any(), any(), any(), any());
	}

	@Test
	void createRejectsBlankContentWithNoImages() {
		Question question = Question.create(100L, 99L, "title", "question");
		setId(question, 200L);
		when(questionRepository.findActiveByIdForShare(200L)).thenReturn(Optional.of(question));

		assertThatThrownBy(() -> service.create(
			principal(),
			200L,
			new CreateAnswerRequest("   ", List.of())
		)).isInstanceOf(InvalidAnswerRequestException.class)
			.hasMessage("content or imageFileIds is required");

		verify(fileRepository, never()).findAllByFileIdInAndUploaderId(any(), any());
		verify(answerRepository, never()).save(any());
	}

	@Test
	void createRejectsNullContentWithNullImages() {
		Question question = Question.create(100L, 99L, "title", "question");
		setId(question, 200L);
		when(questionRepository.findActiveByIdForShare(200L)).thenReturn(Optional.of(question));

		assertThatThrownBy(() -> service.create(
			principal(),
			200L,
			new CreateAnswerRequest(null, null)
		)).isInstanceOf(InvalidAnswerRequestException.class)
			.hasMessage("content or imageFileIds is required");

		verify(answerRepository, never()).save(any());
	}

	@Test
	void createAllowsImagesOnlyAndStoresEmptyContent() {
		UUID imageId = UUID.fromString("00000000-0000-0000-0000-000000000006");
		Question question = Question.create(100L, 99L, "title", "question");
		setId(question, 200L);
		when(questionRepository.findActiveByIdForShare(200L)).thenReturn(Optional.of(question));
		when(fileRepository.findAllByFileIdInAndUploaderId(List.of(imageId), 42L))
			.thenReturn(List.of(uploadedFile(imageId, 42L)));
		when(answerRepository.save(any(Answer.class))).thenAnswer(invocation -> {
			Answer answer = invocation.getArgument(0);
			setId(answer, 301L);
			return answer;
		});

		var response = service.create(principal(), 200L, new CreateAnswerRequest(null, List.of(imageId)));

		assertThat(response.answerId()).isEqualTo(301L);
		ArgumentCaptor<Answer> answerCaptor = ArgumentCaptor.forClass(Answer.class);
		verify(answerRepository).save(answerCaptor.capture());
		assertThat(answerCaptor.getValue().getContent()).isEmpty();
	}

	@Test
	void createRejectsDuplicateImageIds() {
		UUID imageId = UUID.fromString("00000000-0000-0000-0000-000000000004");
		Question question = Question.create(100L, 99L, "title", "question");
		setId(question, 200L);
		when(questionRepository.findActiveByIdForShare(200L)).thenReturn(Optional.of(question));

		assertThatThrownBy(() -> service.create(
			principal(),
			200L,
			new CreateAnswerRequest("answer body", List.of(imageId, imageId))
		)).isInstanceOf(InvalidAnswerRequestException.class)
			.hasMessage("Invalid image");

		verify(answerRepository, never()).save(any());
	}

	@Test
	void createRejectsImageThatIsNotUploadedByCurrentUser() {
		UUID imageId = UUID.fromString("00000000-0000-0000-0000-000000000005");
		Question question = Question.create(100L, 99L, "title", "question");
		setId(question, 200L);
		when(questionRepository.findActiveByIdForShare(200L)).thenReturn(Optional.of(question));
		when(fileRepository.findAllByFileIdInAndUploaderId(List.of(imageId), 42L)).thenReturn(List.of());

		assertThatThrownBy(() -> service.create(
			principal(),
			200L,
			new CreateAnswerRequest("answer body", List.of(imageId))
		)).isInstanceOf(InvalidAnswerRequestException.class)
			.hasMessage("Invalid image");

		verify(answerRepository, never()).save(any());
	}

	@Test
	void finalizeSelectionAcceptsSeveralHumanAndAiAnswersAtomically() {
		Question question = Question.create(100L, 42L, "title", "question");
		setId(question, 200L);
		Answer firstHuman = humanAnswer(200L, 77L, 300L);
		Answer ai = aiAnswer(200L, 301L);
		Answer secondHuman = humanAnswer(200L, 88L, 302L);
		User firstAuthor = user(77L);
		User secondAuthor = user(88L);
		when(questionRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(question));
		when(answerRepository.findAcceptedIdsByQuestionIdOrderByIdAsc(200L)).thenReturn(List.of());
		when(answerRepository.findAllByQuestionIdAndIdInForUpdate(200L, List.of(300L, 301L, 302L)))
			.thenReturn(List.of(firstHuman, ai, secondHuman));
		when(userRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(firstAuthor));
		when(userRepository.findByIdForUpdate(88L)).thenReturn(Optional.of(secondAuthor));

		FinalizeAcceptedAnswersResponse response = service.finalizeSelection(
			principal(),
			200L,
			new FinalizeAcceptedAnswersRequest(List.of(302L, 300L, 301L))
		);

		assertThat(response).isEqualTo(new FinalizeAcceptedAnswersResponse(200L, true, List.of(300L, 301L, 302L)));
		assertThat(firstHuman.isAccepted()).isTrue();
		assertThat(ai.isAccepted()).isTrue();
		assertThat(secondHuman.isAccepted()).isTrue();
		assertThat(question.isResolved()).isTrue();
		assertThat(firstAuthor.getAcceptedCount()).isEqualTo(1);
		assertThat(secondAuthor.getAcceptedCount()).isEqualTo(1);
		verify(notificationPublisher).publishDurableOnce(
			77L,
			NotificationType.question,
			NotificationMessage.of(NotificationMessageKey.ANSWER_ACCEPTED),
			200L,
			false,
			"answer-accepted:300"
		);
		verify(notificationPublisher).publishDurableOnce(
			88L,
			NotificationType.question,
			NotificationMessage.of(NotificationMessageKey.ANSWER_ACCEPTED),
			200L,
			false,
			"answer-accepted:302"
		);
		verify(eventPublisher).publishEvent(new AcceptedHumanAnswerEvent(300L));
		verify(eventPublisher).publishEvent(new AcceptedHumanAnswerEvent(302L));
		verify(notificationPublisher, never()).publishDurableOnce(
			any(),
			any(),
			any(),
			any(),
			any(),
			org.mockito.ArgumentMatchers.eq("answer-accepted:301")
		);
		verify(eventPublisher, never()).publishEvent(new AcceptedHumanAnswerEvent(301L));
		InOrder inOrder = inOrder(questionRepository, answerRepository, userRepository);
		inOrder.verify(questionRepository).findByIdForUpdate(200L);
		inOrder.verify(answerRepository).findAcceptedIdsByQuestionIdOrderByIdAsc(200L);
		inOrder.verify(answerRepository).findAllByQuestionIdAndIdInForUpdate(200L, List.of(300L, 301L, 302L));
		inOrder.verify(userRepository).findByIdForUpdate(77L);
		inOrder.verify(userRepository).findByIdForUpdate(88L);
	}

	@Test
	void finalizeSelectionReturnsCanonicalResponseForSameSetRetryWithoutSideEffects() {
		Question question = Question.create(100L, 42L, "title", "question");
		setId(question, 200L);
		question.markResolved();
		when(questionRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(question));
		when(answerRepository.findAcceptedIdsByQuestionIdOrderByIdAsc(200L)).thenReturn(List.of(300L, 302L));

		FinalizeAcceptedAnswersResponse response = service.finalizeSelection(
			principal(),
			200L,
			new FinalizeAcceptedAnswersRequest(List.of(302L, 300L))
		);

		assertThat(response).isEqualTo(new FinalizeAcceptedAnswersResponse(200L, true, List.of(300L, 302L)));
		verify(answerRepository, never()).findAllByQuestionIdAndIdInForUpdate(any(), any());
		verify(userRepository, never()).findByIdForUpdate(any());
		verify(notificationPublisher, never()).publishDurableOnce(any(), any(), any(), any(), any(), any());
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void finalizeSelectionRejectsDifferentSetAfterFinalization() {
		Question question = Question.create(100L, 42L, "title", "question");
		setId(question, 200L);
		question.markResolved();
		when(questionRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(question));
		when(answerRepository.findAcceptedIdsByQuestionIdOrderByIdAsc(200L)).thenReturn(List.of(300L));

		assertThatThrownBy(() -> service.finalizeSelection(
			principal(),
			200L,
			new FinalizeAcceptedAnswersRequest(List.of(302L))
		)).isInstanceOf(AnswerSelectionFinalizedException.class);

		verify(answerRepository, never()).findAllByQuestionIdAndIdInForUpdate(any(), any());
		verify(userRepository, never()).findByIdForUpdate(any());
		verify(notificationPublisher, never()).publishDurableOnce(any(), any(), any(), any(), any(), any());
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void finalizeSelectionRejectsDuplicateIds() {
		assertThatThrownBy(() -> service.finalizeSelection(
			principal(),
			200L,
			new FinalizeAcceptedAnswersRequest(List.of(300L, 300L))
		)).isInstanceOf(InvalidAnswerRequestException.class)
			.hasMessage("Duplicate answerIds");

		verify(questionRepository, never()).findByIdForUpdate(any());
		verify(answerRepository, never()).findAllByQuestionIdAndIdInForUpdate(any(), any());
		verify(userRepository, never()).findByIdForUpdate(any());
		verify(notificationPublisher, never()).publishDurableOnce(any(), any(), any(), any(), any(), any());
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void finalizeSelectionRejectsNullIdBeforeRepositoryInteractions() {
		assertThatThrownBy(() -> service.finalizeSelection(
			principal(),
			200L,
			new FinalizeAcceptedAnswersRequest(Collections.singletonList(null))
		)).isInstanceOfSatisfying(InvalidAnswerRequestException.class, exception -> {
			assertThat(exception.code()).isEqualTo("VALIDATION_FAILED");
			assertThat(exception.field()).isEqualTo("answerIds");
			assertThat(exception).hasMessage("Invalid answerIds");
		});

		verify(questionRepository, never()).findByIdForUpdate(any());
		verify(answerRepository, never()).findAcceptedIdsByQuestionIdOrderByIdAsc(any());
		verify(answerRepository, never()).findAllByQuestionIdAndIdInForUpdate(any(), any());
		verify(userRepository, never()).findByIdForUpdate(any());
		verify(notificationPublisher, never()).publishDurableOnce(any(), any(), any(), any(), any(), any());
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void finalizeSelectionRejectsZeroIdBeforeRepositoryInteractions() {
		assertThatThrownBy(() -> service.finalizeSelection(
			principal(),
			200L,
			new FinalizeAcceptedAnswersRequest(List.of(0L))
		)).isInstanceOfSatisfying(InvalidAnswerRequestException.class, exception -> {
			assertThat(exception.code()).isEqualTo("VALIDATION_FAILED");
			assertThat(exception.field()).isEqualTo("answerIds");
			assertThat(exception).hasMessage("Invalid answerIds");
		});

		verify(questionRepository, never()).findByIdForUpdate(any());
		verify(answerRepository, never()).findAcceptedIdsByQuestionIdOrderByIdAsc(any());
		verify(answerRepository, never()).findAllByQuestionIdAndIdInForUpdate(any(), any());
		verify(userRepository, never()).findByIdForUpdate(any());
		verify(notificationPublisher, never()).publishDurableOnce(any(), any(), any(), any(), any(), any());
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void finalizeSelectionRejectsNegativeIdBeforeRepositoryInteractions() {
		assertThatThrownBy(() -> service.finalizeSelection(
			principal(),
			200L,
			new FinalizeAcceptedAnswersRequest(List.of(-1L))
		)).isInstanceOfSatisfying(InvalidAnswerRequestException.class, exception -> {
			assertThat(exception.code()).isEqualTo("VALIDATION_FAILED");
			assertThat(exception.field()).isEqualTo("answerIds");
			assertThat(exception).hasMessage("Invalid answerIds");
		});

		verify(questionRepository, never()).findByIdForUpdate(any());
		verify(answerRepository, never()).findAcceptedIdsByQuestionIdOrderByIdAsc(any());
		verify(answerRepository, never()).findAllByQuestionIdAndIdInForUpdate(any(), any());
		verify(userRepository, never()).findByIdForUpdate(any());
		verify(notificationPublisher, never()).publishDurableOnce(any(), any(), any(), any(), any(), any());
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void finalizeSelectionRejectsMissingOrForeignAnswerWithoutPartialChanges() {
		Question question = Question.create(100L, 42L, "title", "question");
		setId(question, 200L);
		Answer answer = humanAnswer(200L, 77L, 300L);
		when(questionRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(question));
		when(answerRepository.findAcceptedIdsByQuestionIdOrderByIdAsc(200L)).thenReturn(List.of());
		when(answerRepository.findAllByQuestionIdAndIdInForUpdate(200L, List.of(300L, 999L)))
			.thenReturn(List.of(answer));

		assertThatThrownBy(() -> service.finalizeSelection(
			principal(),
			200L,
			new FinalizeAcceptedAnswersRequest(List.of(999L, 300L))
		)).isInstanceOf(AnswerNotFoundException.class);

		assertThat(answer.isAccepted()).isFalse();
		assertThat(question.isResolved()).isFalse();
		verify(userRepository, never()).findByIdForUpdate(any());
		verify(notificationPublisher, never()).publishDurableOnce(any(), any(), any(), any(), any(), any());
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void finalizeSelectionRejectsQuestionAuthorsHumanAnswer() {
		Question question = Question.create(100L, 42L, "title", "question");
		setId(question, 200L);
		Answer selfAnswer = humanAnswer(200L, 42L, 300L);
		when(questionRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(question));
		when(answerRepository.findAcceptedIdsByQuestionIdOrderByIdAsc(200L)).thenReturn(List.of());
		when(answerRepository.findAllByQuestionIdAndIdInForUpdate(200L, List.of(300L))).thenReturn(List.of(selfAnswer));

		assertThatThrownBy(() -> service.finalizeSelection(
			principal(),
			200L,
			new FinalizeAcceptedAnswersRequest(List.of(300L))
		)).isInstanceOf(SelfAcceptanceNotAllowedException.class);

		assertThat(selfAnswer.isAccepted()).isFalse();
		assertThat(question.isResolved()).isFalse();
		verify(userRepository, never()).findByIdForUpdate(any());
		verify(notificationPublisher, never()).publishDurableOnce(any(), any(), any(), any(), any(), any());
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void finalizeSelectionCountsAndNotifiesEachSelectedHumanAnswer() {
		Question question = Question.create(100L, 42L, "title", "question");
		setId(question, 200L);
		Answer firstAnswer = humanAnswer(200L, 77L, 300L);
		Answer secondAnswer = humanAnswer(200L, 77L, 301L);
		User author = user(77L);
		when(questionRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(question));
		when(answerRepository.findAcceptedIdsByQuestionIdOrderByIdAsc(200L)).thenReturn(List.of());
		when(answerRepository.findAllByQuestionIdAndIdInForUpdate(200L, List.of(300L, 301L)))
			.thenReturn(List.of(firstAnswer, secondAnswer));
		when(userRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(author));

		service.finalizeSelection(principal(), 200L, new FinalizeAcceptedAnswersRequest(List.of(301L, 300L)));

		assertThat(author.getAcceptedCount()).isEqualTo(2);
		verify(userRepository).findByIdForUpdate(77L);
		verify(notificationPublisher).publishDurableOnce(
			77L,
			NotificationType.question,
			NotificationMessage.of(NotificationMessageKey.ANSWER_ACCEPTED),
			200L,
			false,
			"answer-accepted:300"
		);
		verify(notificationPublisher).publishDurableOnce(
			77L,
			NotificationType.question,
			NotificationMessage.of(NotificationMessageKey.ANSWER_ACCEPTED),
			200L,
			false,
			"answer-accepted:301"
		);
		verify(eventPublisher).publishEvent(new AcceptedHumanAnswerEvent(300L));
		verify(eventPublisher).publishEvent(new AcceptedHumanAnswerEvent(301L));
	}

	private AuthenticatedUser principal() {
		return new AuthenticatedUser(42L, "user@example.com", UserRole.user, UserStatus.active);
	}

	private Answer humanAnswer(Long questionId, Long authorId, Long id) {
		Answer answer = Answer.createHuman(questionId, authorId, "answer");
		setId(answer, id);
		return answer;
	}

	private Answer aiAnswer(Long questionId, Long id) {
		Answer answer = Answer.createAi(questionId, "ai answer");
		setId(answer, id);
		return answer;
	}

	private File uploadedFile(UUID fileId, Long uploaderId) {
		File file = File.pending(fileId, uploaderId, "answers/%s".formatted(fileId), "image/jpeg", 1024L);
		file.markUploaded(OffsetDateTime.now(), "image/jpeg", 1024L);
		return file;
	}

	private User user(Long id) {
		User user = User.createEmailUser(
			"user%s@example.com".formatted(id),
			"hash",
			"nickname%s".formatted(id),
			LocalDate.of(1995, 5, 20),
			GenderType.female,
			"KR"
		);
		setId(user, id);
		return user;
	}

	private void setId(Question question, Long id) {
		setField(question, Question.class, "id", id);
	}

	private void setId(Answer answer, Long id) {
		setField(answer, Answer.class, "id", id);
	}

	private void setId(User user, Long id) {
		setField(user, User.class, "id", id);
	}

	private void setField(Object target, Class<?> type, String fieldName, Object value) {
		try {
			java.lang.reflect.Field field = type.getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
