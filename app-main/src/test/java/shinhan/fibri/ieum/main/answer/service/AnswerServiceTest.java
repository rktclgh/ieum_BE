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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserGrade;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.file.domain.File;
import shinhan.fibri.ieum.common.file.repository.FileRepository;
import shinhan.fibri.ieum.main.answer.domain.Answer;
import shinhan.fibri.ieum.main.answer.domain.AnswerImage;
import shinhan.fibri.ieum.main.answer.dto.CreateAnswerRequest;
import shinhan.fibri.ieum.main.answer.exception.AnswerNotFoundException;
import shinhan.fibri.ieum.main.answer.exception.InvalidAnswerRequestException;
import shinhan.fibri.ieum.main.answer.exception.QuestionAlreadyResolvedException;
import shinhan.fibri.ieum.main.answer.exception.SelfAcceptanceNotAllowedException;
import shinhan.fibri.ieum.main.answer.repository.AnswerImageRepository;
import shinhan.fibri.ieum.main.answer.repository.AnswerRepository;
import shinhan.fibri.ieum.main.question.domain.Question;
import shinhan.fibri.ieum.main.question.exception.QuestionForbiddenException;
import shinhan.fibri.ieum.main.question.exception.QuestionNotFoundException;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;

class AnswerServiceTest {

	private final QuestionRepository questionRepository = mock(QuestionRepository.class);
	private final AnswerRepository answerRepository = mock(AnswerRepository.class);
	private final AnswerImageRepository answerImageRepository = mock(AnswerImageRepository.class);
	private final FileRepository fileRepository = mock(FileRepository.class);
	private final UserRepository userRepository = mock(UserRepository.class);
	private final AnswerService service = new AnswerService(
		questionRepository,
		answerRepository,
		answerImageRepository,
		fileRepository,
		userRepository
	);

	@Test
	void createValidatesQuestionAndImagesThenSavesAnswerAndImageLinksInOrder() {
		UUID firstImageId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID secondImageId = UUID.fromString("00000000-0000-0000-0000-000000000002");
		Question question = Question.create(100L, 99L, "title", "question");
		setId(question, 200L);
		when(questionRepository.existsById(200L)).thenReturn(true);
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
		inOrder.verify(questionRepository).existsById(200L);
		inOrder.verify(fileRepository, times(1)).findAllByFileIdInAndUploaderId(List.of(firstImageId, secondImageId), 42L);
		inOrder.verify(answerRepository).save(any(Answer.class));
		inOrder.verify(answerImageRepository).saveAll(any());
		verify(fileRepository, never()).findByFileIdAndUploaderId(any(), any());
	}

	@Test
	void createRejectsMissingQuestionBeforeValidatingImages() {
		UUID imageId = UUID.fromString("00000000-0000-0000-0000-000000000003");
		when(questionRepository.existsById(999L)).thenReturn(false);

		assertThatThrownBy(() -> service.create(
			principal(),
			999L,
			new CreateAnswerRequest("answer body", List.of(imageId))
		)).isInstanceOf(QuestionNotFoundException.class);

		verify(fileRepository, never()).findAllByFileIdInAndUploaderId(any(), any());
		verify(answerRepository, never()).save(any());
	}

	@Test
	void createRejectsBlankContentWithNoImages() {
		Question question = Question.create(100L, 99L, "title", "question");
		setId(question, 200L);
		when(questionRepository.existsById(200L)).thenReturn(true);

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
		when(questionRepository.existsById(200L)).thenReturn(true);

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
		when(questionRepository.existsById(200L)).thenReturn(true);
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
		when(questionRepository.existsById(200L)).thenReturn(true);

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
		when(questionRepository.existsById(200L)).thenReturn(true);
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
	void acceptMarksAnswerAndQuestionThenRecordsHumanAuthorAcceptance() {
		Answer answer = Answer.createHuman(200L, 77L, "answer");
		setId(answer, 300L);
		Question question = Question.create(100L, 42L, "title", "question");
		setId(question, 200L);
		User answerAuthor = user(77L);
		for (int i = 0; i < 4; i++) {
			answerAuthor.recordAcceptedAnswer();
		}
		when(answerRepository.findById(300L)).thenReturn(Optional.of(answer));
		when(questionRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(question));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(answerAuthor));

		service.accept(principal(), 300L);

		assertThat(answer.isAccepted()).isTrue();
		assertThat(question.isResolved()).isTrue();
		assertThat(answerAuthor.getAcceptedCount()).isEqualTo(5);
		assertThat(answerAuthor.getGrade()).isEqualTo(UserGrade.silver);
	}

	@Test
	void acceptRejectsMissingAnswer() {
		when(answerRepository.findById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.accept(principal(), 999L))
			.isInstanceOf(AnswerNotFoundException.class);

		verify(questionRepository, never()).findByIdForUpdate(any());
	}

	@Test
	void acceptRejectsNonQuestionAuthor() {
		Answer answer = Answer.createHuman(200L, 77L, "answer");
		setId(answer, 300L);
		Question question = Question.create(100L, 99L, "title", "question");
		setId(question, 200L);
		when(answerRepository.findById(300L)).thenReturn(Optional.of(answer));
		when(questionRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(question));

		assertThatThrownBy(() -> service.accept(principal(), 300L))
			.isInstanceOf(QuestionForbiddenException.class);

		assertThat(answer.isAccepted()).isFalse();
		assertThat(question.isResolved()).isFalse();
	}

	@Test
	void acceptRejectsAlreadyResolvedQuestion() {
		Answer answer = Answer.createHuman(200L, 77L, "answer");
		setId(answer, 300L);
		Question question = Question.create(100L, 42L, "title", "question");
		setId(question, 200L);
		question.markResolved();
		when(answerRepository.findById(300L)).thenReturn(Optional.of(answer));
		when(questionRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(question));

		assertThatThrownBy(() -> service.accept(principal(), 300L))
			.isInstanceOf(QuestionAlreadyResolvedException.class);

		assertThat(answer.isAccepted()).isFalse();
		verify(userRepository, never()).findByIdAndDeletedAtIsNull(any());
	}

	@Test
	void acceptRejectsAnswerWrittenByQuestionAuthor() {
		Answer answer = Answer.createHuman(200L, 42L, "self answer");
		setId(answer, 300L);
		Question question = Question.create(100L, 42L, "title", "question");
		setId(question, 200L);
		when(answerRepository.findById(300L)).thenReturn(Optional.of(answer));
		when(questionRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(question));

		assertThatThrownBy(() -> service.accept(principal(), 300L))
			.isInstanceOf(SelfAcceptanceNotAllowedException.class);

		assertThat(answer.isAccepted()).isFalse();
		assertThat(question.isResolved()).isFalse();
		verify(userRepository, never()).findByIdAndDeletedAtIsNull(any());
	}

	@Test
	void acceptSkipsUserGradeUpdateForAiAnswer() {
		Answer answer = Answer.createAi(200L, "ai answer");
		setId(answer, 300L);
		Question question = Question.create(100L, 42L, "title", "question");
		setId(question, 200L);
		when(answerRepository.findById(300L)).thenReturn(Optional.of(answer));
		when(questionRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(question));

		service.accept(principal(), 300L);

		assertThat(answer.isAccepted()).isTrue();
		assertThat(question.isResolved()).isTrue();
		verify(userRepository, never()).findByIdAndDeletedAtIsNull(any());
	}

	private AuthenticatedUser principal() {
		return new AuthenticatedUser(42L, "user@example.com", UserRole.user, UserStatus.active);
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
