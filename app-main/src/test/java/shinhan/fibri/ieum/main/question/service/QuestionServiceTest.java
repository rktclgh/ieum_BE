package shinhan.fibri.ieum.main.question.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
import shinhan.fibri.ieum.main.ai.question.repository.QuestionAnswerTicketWriter;
import shinhan.fibri.ieum.main.answer.domain.AnswerImage;
import shinhan.fibri.ieum.main.answer.repository.AnswerImageRepository;
import shinhan.fibri.ieum.main.pin.domain.PinType;
import shinhan.fibri.ieum.main.pin.dto.LocationSnapshot;
import shinhan.fibri.ieum.main.pin.repository.PinWriter;
import shinhan.fibri.ieum.main.question.domain.Question;
import shinhan.fibri.ieum.main.question.domain.QuestionImage;
import shinhan.fibri.ieum.main.question.dto.AnswerItem;
import shinhan.fibri.ieum.main.question.dto.QuestionCreateRequest;
import shinhan.fibri.ieum.main.question.dto.QuestionDetailResponse;
import shinhan.fibri.ieum.main.question.exception.InvalidQuestionRequestException;
import shinhan.fibri.ieum.main.question.exception.QuestionForbiddenException;
import shinhan.fibri.ieum.main.question.exception.QuestionNotFoundException;
import shinhan.fibri.ieum.main.question.repository.AnswerItemProjection;
import shinhan.fibri.ieum.main.question.repository.MyQuestionItemProjection;
import shinhan.fibri.ieum.main.question.repository.QuestionDetailProjection;
import shinhan.fibri.ieum.main.question.repository.QuestionDeletionState;
import shinhan.fibri.ieum.main.question.repository.QuestionImageRepository;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;
import shinhan.fibri.ieum.main.notification.presence.QuestionCreatedEvent;

class QuestionServiceTest {

	private final QuestionRepository questionRepository = mock(QuestionRepository.class);
	private final QuestionImageRepository questionImageRepository = mock(QuestionImageRepository.class);
	private final AnswerImageRepository answerImageRepository = mock(AnswerImageRepository.class);
	private final FileRepository fileRepository = mock(FileRepository.class);
	private final UserRepository userRepository = mock(UserRepository.class);
	private final PinWriter pinWriter = mock(PinWriter.class);
	private final QuestionAnswerTicketWriter questionAnswerTicketWriter = mock(QuestionAnswerTicketWriter.class);
	private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
	private final QuestionService service = new QuestionService(
		questionRepository,
		questionImageRepository,
		answerImageRepository,
		fileRepository,
		userRepository,
		pinWriter,
		questionAnswerTicketWriter,
		eventPublisher
	);

	@Test
	void createValidatesImagesCreatesPinQuestionAndImageLinksInOrder() {
		UUID imageId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID profileId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		File uploadedFile = uploadedFile(imageId, 42L);
		User author = user(profileId);
		when(fileRepository.findByFileIdAndUploaderId(imageId, 42L)).thenReturn(Optional.of(uploadedFile));
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(author));
		LocationSnapshot location = new LocationSnapshot(37.4979, 127.0276, "서울특별시 강남구", "", "강남역");
		when(pinWriter.create(42L, PinType.question, location)).thenReturn(100L);
		when(questionRepository.saveAndFlush(any(Question.class))).thenAnswer(invocation -> {
			Question question = invocation.getArgument(0);
			setId(question, 200L);
			return question;
		});

		QuestionDetailResponse response = service.create(
			principal(),
			new QuestionCreateRequest(
				"title",
				"content",
				location,
				List.of(imageId)
			)
		);

		assertThat(response.questionId()).isEqualTo(200L);
		assertThat(response.title()).isEqualTo("title");
		assertThat(response.isResolved()).isFalse();
		assertThat(response.answerSelectionFinalized()).isFalse();
		assertThat(response.author().profileImageUrl()).isEqualTo("/api/v1/files/%s".formatted(profileId));
		assertThat(response.location()).isEqualTo(location);
		assertThat(response.imageUrls()).containsExactly("/api/v1/files/%s?v=display".formatted(imageId));
		InOrder inOrder = inOrder(
			fileRepository,
			pinWriter,
			questionRepository,
			questionImageRepository,
			questionAnswerTicketWriter,
			eventPublisher
		);
		inOrder.verify(fileRepository).findByFileIdAndUploaderId(imageId, 42L);
		inOrder.verify(pinWriter).create(42L, PinType.question, location);
		inOrder.verify(questionRepository).saveAndFlush(any(Question.class));
		inOrder.verify(questionImageRepository).saveAll(any());
		inOrder.verify(questionAnswerTicketWriter).create(200L);
		inOrder.verify(eventPublisher).publishEvent(
			new QuestionCreatedEvent(200L, 42L, "title", 37.4979, 127.0276)
		);
	}

	@Test
	void createRejectsImageThatDoesNotBelongToCurrentUser() {
		UUID imageId = UUID.fromString("00000000-0000-0000-0000-000000000002");
		when(fileRepository.findByFileIdAndUploaderId(imageId, 42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.create(
			principal(),
			new QuestionCreateRequest(
				"title",
				"content",
				new LocationSnapshot(37.4979, 127.0276, "서울특별시 강남구", "", "강남역"),
				List.of(imageId)
			)
		)).isInstanceOf(InvalidQuestionRequestException.class)
			.hasMessage("Invalid image");

		verify(pinWriter, never()).create(any(), any(), any(LocationSnapshot.class));
	}

	@Test
	void getDetailAssemblesDisplayImageUrlsInSortOrder() {
		UUID first = UUID.fromString("00000000-0000-0000-0000-000000000011");
		UUID second = UUID.fromString("00000000-0000-0000-0000-000000000012");
		UUID profileId = UUID.fromString("00000000-0000-0000-0000-000000000102");
		when(questionRepository.findDetailByQuestionId(200L)).thenReturn(Optional.of(
			new DetailProjection(200L, "title", "content", false, 42L, "nickname", profileId)
		));
		when(questionImageRepository.findByQuestionIdOrderBySortOrderAsc(200L))
			.thenReturn(List.of(
				QuestionImage.link(200L, first, 0),
				QuestionImage.link(200L, second, 1)
			));

		QuestionDetailResponse response = service.getDetail(200L);

		assertThat(response.questionId()).isEqualTo(200L);
		assertThat(response.isResolved()).isFalse();
		assertThat(response.answerSelectionFinalized()).isFalse();
		assertThat(response.imageUrls()).containsExactly(
			"/api/v1/files/%s?v=display".formatted(first),
			"/api/v1/files/%s?v=display".formatted(second)
		);
		assertThat(response.author().profileImageUrl()).isEqualTo("/api/v1/files/%s".formatted(profileId));
		assertThat(response.location().address()).isEqualTo("서울특별시 강남구");
		assertThat(response.answers()).isEmpty();
		verify(answerImageRepository, never()).findByAnswerIdInOrderBySortOrderAsc(any());
	}

	@Test
	void getDetailAssemblesHumanAiAnswersAndAnswerImages() {
		UUID answerFirstImage = UUID.fromString("00000000-0000-0000-0000-000000000041");
		UUID answerSecondImage = UUID.fromString("00000000-0000-0000-0000-000000000042");
		UUID aiAnswerImage = UUID.fromString("00000000-0000-0000-0000-000000000043");
		UUID answerAuthorProfileId = UUID.fromString("00000000-0000-0000-0000-000000000105");
		when(questionRepository.findDetailByQuestionId(200L)).thenReturn(Optional.of(
			new DetailProjection(200L, "title", "content", true, 42L, "questioner", null)
		));
		when(questionImageRepository.findByQuestionIdOrderBySortOrderAsc(200L)).thenReturn(List.of());
		when(questionRepository.findAnswersByQuestionId(200L)).thenReturn(List.of(
			new AnswerProjection(
				300L,
				false,
				77L,
				"answerer",
				answerAuthorProfileId,
				"KR",
				"human answer",
				true,
				Instant.parse("2026-07-08T10:00:00Z")
			),
			new AnswerProjection(
				301L,
				true,
				null,
				null,
				null,
				null,
				"ai answer",
				false,
				Instant.parse("2026-07-08T10:01:00Z")
			)
		));
		when(answerImageRepository.findByAnswerIdInOrderBySortOrderAsc(List.of(300L, 301L)))
			.thenReturn(List.of(
				AnswerImage.link(300L, answerFirstImage, 0),
				AnswerImage.link(300L, answerSecondImage, 1),
				AnswerImage.link(301L, aiAnswerImage, 0)
			));

		QuestionDetailResponse response = service.getDetail(200L);

		assertThat(response.isResolved()).isTrue();
		assertThat(response.answerSelectionFinalized()).isTrue();
		assertThat(response.answers()).hasSize(2);
		AnswerItem humanAnswer = response.answers().get(0);
		assertThat(humanAnswer.answerId()).isEqualTo(300L);
		assertThat(humanAnswer.isAi()).isFalse();
		assertThat(humanAnswer.author().userId()).isEqualTo(77L);
		assertThat(humanAnswer.author().nickname()).isEqualTo("answerer");
		assertThat(humanAnswer.author().profileImageUrl()).isEqualTo("/api/v1/files/%s".formatted(answerAuthorProfileId));
		assertThat(humanAnswer.author().nationality()).isEqualTo("KR");
		assertThat(humanAnswer.content()).isEqualTo("human answer");
		assertThat(humanAnswer.isAccepted()).isTrue();
		assertThat(humanAnswer.imageUrls()).containsExactly(
			"/api/v1/files/%s?v=display".formatted(answerFirstImage),
			"/api/v1/files/%s?v=display".formatted(answerSecondImage)
		);
		assertThat(humanAnswer.createdAt()).isEqualTo(Instant.parse("2026-07-08T10:00:00Z").atOffset(java.time.ZoneOffset.UTC));

		AnswerItem aiAnswer = response.answers().get(1);
		assertThat(aiAnswer.answerId()).isEqualTo(301L);
		assertThat(aiAnswer.isAi()).isTrue();
		assertThat(aiAnswer.author()).isNull();
		assertThat(aiAnswer.content()).isEqualTo("ai answer");
		assertThat(aiAnswer.isAccepted()).isFalse();
		assertThat(aiAnswer.imageUrls()).containsExactly("/api/v1/files/%s?v=display".formatted(aiAnswerImage));
	}

	@Test
	void listMineUsesThumbnailUrlAndNextCursorFromLookahead() {
		UUID thumbnail = UUID.fromString("00000000-0000-0000-0000-000000000021");
		when(questionRepository.findMineFirstPage(42L, 2)).thenReturn(List.of(
			new MineProjection(300L, "newer", false, thumbnail, 1, Instant.parse("2026-07-08T10:00:00Z")),
			new MineProjection(200L, "older", true, null, 0, Instant.parse("2026-07-07T10:00:00Z"))
		));

		var page = service.listMine(principal(), null, 1);

		assertThat(page.items()).hasSize(1);
		assertThat(page.items().get(0).isResolved()).isFalse();
		assertThat(page.items().get(0).answerSelectionFinalized()).isFalse();
		assertThat(page.items().get(0).thumbnailUrl()).isEqualTo("/api/v1/files/%s?v=thumb".formatted(thumbnail));
		assertThat(page.nextCursor()).isEqualTo("MjAw");
	}

	@Test
	void listMineUsesDecodedCursorQueryWhenCursorIsPresent() {
		when(questionRepository.findMineAfterCursor(42L, 200L, 2)).thenReturn(List.of(
			new MineProjection(100L, "older", true, null, 0, Instant.parse("2026-07-06T10:00:00Z"))
		));

		var page = service.listMine(principal(), "MjAw", 1);

		assertThat(page.items()).hasSize(1);
		assertThat(page.items().get(0).questionId()).isEqualTo(100L);
		assertThat(page.items().get(0).isResolved()).isTrue();
		assertThat(page.items().get(0).answerSelectionFinalized()).isTrue();
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void deleteRequestsTicketCancellationBeforeLockingAndSoftDeletesQuestionAndPinAtSameTime() {
		QuestionDeletionState state = deletionState(42L, null);
		Question question = Question.create(100L, 42L, "title", "content");
		setId(question, 200L);
		when(questionRepository.findDeletionState(200L)).thenReturn(Optional.of(state));
		when(questionRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(question));

		service.delete(principal(), 200L);

		InOrder inOrder = inOrder(questionRepository, questionAnswerTicketWriter, pinWriter);
		inOrder.verify(questionRepository).findDeletionState(200L);
		inOrder.verify(questionAnswerTicketWriter).requestCancellation(200L);
		inOrder.verify(questionRepository).findByIdForUpdate(200L);
		ArgumentCaptor<OffsetDateTime> deletedAt = ArgumentCaptor.forClass(OffsetDateTime.class);
		inOrder.verify(pinWriter).softDelete(eq(100L), deletedAt.capture());
		assertThat(question.getDeletedAt()).isEqualTo(deletedAt.getValue());
	}

	@Test
	void deleteIsIdempotentForSameAuthorWhenQuestionIsAlreadyDeleted() {
		Instant deletedAt = Instant.parse("2026-07-13T10:00:00Z");
		QuestionDeletionState state = deletionState(42L, deletedAt);
		when(questionRepository.findDeletionState(200L)).thenReturn(Optional.of(state));

		service.delete(principal(), 200L);

		verify(questionAnswerTicketWriter, never()).requestCancellation(any());
		verify(questionRepository, never()).findByIdForUpdate(any());
		verify(pinWriter, never()).softDelete(any(), any());
	}

	@Test
	void deleteThrowsNotFoundWhenQuestionDoesNotExist() {
		when(questionRepository.findDeletionState(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.delete(principal(), 999L))
			.isInstanceOf(QuestionNotFoundException.class);

		verify(questionAnswerTicketWriter, never()).requestCancellation(any());
	}

	@Test
	void deleteRejectsNonAuthorBeforeRequestingCancellation() {
		QuestionDeletionState state = deletionState(99L, null);
		when(questionRepository.findDeletionState(200L)).thenReturn(Optional.of(state));

		assertThatThrownBy(() -> service.delete(principal(), 200L))
			.isInstanceOf(QuestionForbiddenException.class);

		verify(questionAnswerTicketWriter, never()).requestCancellation(any());
		verify(questionRepository, never()).findByIdForUpdate(any());
	}

	@Test
	void deleteRechecksStateAfterLockAndAcceptsConcurrentSameAuthorDelete() {
		Question question = Question.create(100L, 42L, "title", "content");
		question.softDelete(OffsetDateTime.parse("2026-07-13T10:00:00Z"));
		setId(question, 200L);
		QuestionDeletionState state = deletionState(42L, null);
		when(questionRepository.findDeletionState(200L)).thenReturn(Optional.of(state));
		when(questionRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(question));

		service.delete(principal(), 200L);

		verify(questionAnswerTicketWriter).requestCancellation(200L);
		verify(pinWriter, never()).softDelete(any(), any());
	}

	@Test
	void deleteAcceptsQuestionBecomingInvisibleAfterPrecheckAsConcurrentDelete() {
		QuestionDeletionState state = deletionState(42L, null);
		when(questionRepository.findDeletionState(200L)).thenReturn(Optional.of(state));
		when(questionRepository.findByIdForUpdate(200L)).thenReturn(Optional.empty());

		service.delete(principal(), 200L);

		verify(questionAnswerTicketWriter).requestCancellation(200L);
		verify(pinWriter, never()).softDelete(any(), any());
	}

	private AuthenticatedUser principal() {
		return new AuthenticatedUser(42L, "user@example.com", UserRole.user, UserStatus.active);
	}

	private User user() {
		return user(null);
	}

	private User user(UUID profileFileId) {
		User user = User.createEmailUser(
			"user@example.com",
			"hash",
			"nickname",
			LocalDate.of(1995, 5, 20),
			GenderType.female,
			"KR"
		);
		setId(user, 42L);
		if (profileFileId != null) {
			user.linkProfileImage(profileFileId);
		}
		return user;
	}

	private File uploadedFile(UUID fileId, Long uploaderId) {
		File file = File.pending(fileId, uploaderId, "questions/%s".formatted(fileId), "image/jpeg", 1024L);
		file.markUploaded(OffsetDateTime.now(), "image/jpeg", 1024L);
		return file;
	}

	private QuestionDeletionState deletionState(Long authorId, Instant deletedAt) {
		QuestionDeletionState state = mock(QuestionDeletionState.class);
		when(state.getAuthorId()).thenReturn(authorId);
		when(state.getDeletedAt()).thenReturn(deletedAt);
		return state;
	}

	private void setId(Question question, Long id) {
		try {
			java.lang.reflect.Field field = Question.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(question, id);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static final class DetailProjection implements QuestionDetailProjection {

		private final Long questionId;
		private final String title;
		private final String content;
		private final boolean resolved;
		private final Long authorId;
		private final String authorNickname;
		private final UUID authorProfileFileId;

		private DetailProjection(
			Long questionId,
			String title,
			String content,
			boolean resolved,
			Long authorId,
			String authorNickname,
			UUID authorProfileFileId
		) {
			this.questionId = questionId;
			this.title = title;
			this.content = content;
			this.resolved = resolved;
			this.authorId = authorId;
			this.authorNickname = authorNickname;
			this.authorProfileFileId = authorProfileFileId;
		}

		@Override
		public Long getQuestionId() {
			return questionId;
		}

		@Override
		public String getTitle() {
			return title;
		}

		@Override
		public String getContent() {
			return content;
		}

		@Override
		public boolean getResolved() {
			return resolved;
		}

		@Override
		public Long getAuthorId() {
			return authorId;
		}

		@Override
		public String getAuthorNickname() {
			return authorNickname;
		}

		@Override
		public UUID getAuthorProfileFileId() {
			return authorProfileFileId;
		}

		@Override
		public String getAuthorNationality() {
			return "KR";
		}

		@Override
		public double getLatitude() {
			return 37.4979;
		}

		@Override
		public double getLongitude() {
			return 127.0276;
		}

		@Override
		public String getAddress() {
			return "서울특별시 강남구";
		}

		@Override
		public String getDetailAddress() {
			return "";
		}

		@Override
		public String getLabel() {
			return "강남역";
		}

		@Override
		public Instant getCreatedAt() {
			return Instant.parse("2026-07-14T00:00:00Z");
		}

		@Override
		public Instant getUpdatedAt() {
			return Instant.parse("2026-07-14T00:00:00Z");
		}
	}

	private static final class MineProjection implements MyQuestionItemProjection {

		private final Long questionId;
		private final String title;
		private final boolean resolved;
		private final UUID thumbnailFileId;
		private final int answerCount;
		private final Instant createdAt;

		private MineProjection(
			Long questionId,
			String title,
			boolean resolved,
			UUID thumbnailFileId,
			int answerCount,
			Instant createdAt
		) {
			this.questionId = questionId;
			this.title = title;
			this.resolved = resolved;
			this.thumbnailFileId = thumbnailFileId;
			this.answerCount = answerCount;
			this.createdAt = createdAt;
		}

		@Override
		public Long getQuestionId() {
			return questionId;
		}

		@Override
		public String getTitle() {
			return title;
		}

		@Override
		public boolean getResolved() {
			return resolved;
		}

		@Override
		public UUID getThumbnailFileId() {
			return thumbnailFileId;
		}

		@Override
		public int getAnswerCount() {
			return answerCount;
		}

		@Override
		public Instant getCreatedAt() {
			return createdAt;
		}
	}

	private static final class AnswerProjection implements AnswerItemProjection {

		private final Long answerId;
		private final boolean ai;
		private final Long authorId;
		private final String authorNickname;
		private final UUID authorProfileFileId;
		private final String authorNationality;
		private final String content;
		private final boolean accepted;
		private final Instant createdAt;

		private AnswerProjection(
			Long answerId,
			boolean ai,
			Long authorId,
			String authorNickname,
			UUID authorProfileFileId,
			String authorNationality,
			String content,
			boolean accepted,
			Instant createdAt
		) {
			this.answerId = answerId;
			this.ai = ai;
			this.authorId = authorId;
			this.authorNickname = authorNickname;
			this.authorProfileFileId = authorProfileFileId;
			this.authorNationality = authorNationality;
			this.content = content;
			this.accepted = accepted;
			this.createdAt = createdAt;
		}

		@Override
		public Long getAnswerId() {
			return answerId;
		}

		@Override
		public boolean getAi() {
			return ai;
		}

		@Override
		public Long getAuthorId() {
			return authorId;
		}

		@Override
		public String getAuthorNickname() {
			return authorNickname;
		}

		@Override
		public UUID getAuthorProfileFileId() {
			return authorProfileFileId;
		}

		@Override
		public String getAuthorNationality() {
			return authorNationality;
		}

		@Override
		public String getContent() {
			return content;
		}

		@Override
		public boolean getAccepted() {
			return accepted;
		}

		@Override
		public Instant getCreatedAt() {
			return createdAt;
		}
	}

	private void setId(User user, Long id) {
		try {
			java.lang.reflect.Field field = User.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(user, id);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
