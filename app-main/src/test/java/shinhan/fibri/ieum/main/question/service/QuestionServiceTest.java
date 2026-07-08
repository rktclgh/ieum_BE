package shinhan.fibri.ieum.main.question.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.file.domain.File;
import shinhan.fibri.ieum.common.file.repository.FileRepository;
import shinhan.fibri.ieum.main.pin.domain.PinType;
import shinhan.fibri.ieum.main.pin.repository.PinWriter;
import shinhan.fibri.ieum.main.question.domain.Question;
import shinhan.fibri.ieum.main.question.dto.QuestionCreateRequest;
import shinhan.fibri.ieum.main.question.dto.QuestionDetailResponse;
import shinhan.fibri.ieum.main.question.dto.QuestionLocation;
import shinhan.fibri.ieum.main.question.dto.QuestionUpdateRequest;
import shinhan.fibri.ieum.main.question.exception.InvalidQuestionRequestException;
import shinhan.fibri.ieum.main.question.exception.QuestionForbiddenException;
import shinhan.fibri.ieum.main.question.domain.QuestionImage;
import shinhan.fibri.ieum.main.question.repository.MyQuestionItemProjection;
import shinhan.fibri.ieum.main.question.repository.QuestionDetailProjection;
import shinhan.fibri.ieum.main.question.repository.QuestionImageRepository;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;

class QuestionServiceTest {

	private final QuestionRepository questionRepository = mock(QuestionRepository.class);
	private final QuestionImageRepository questionImageRepository = mock(QuestionImageRepository.class);
	private final FileRepository fileRepository = mock(FileRepository.class);
	private final UserRepository userRepository = mock(UserRepository.class);
	private final PinWriter pinWriter = mock(PinWriter.class);
	private final QuestionImageCleanupService imageCleanupService = mock(QuestionImageCleanupService.class);
	private final QuestionService service = new QuestionService(
		questionRepository,
		questionImageRepository,
		fileRepository,
		userRepository,
		pinWriter,
		imageCleanupService
	);

	@Test
	void createValidatesImagesCreatesPinQuestionAndImageLinksInOrder() {
		UUID imageId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID profileId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		File uploadedFile = uploadedFile(imageId, 42L);
		User author = user(profileId);
		when(fileRepository.findByFileIdAndUploaderId(imageId, 42L)).thenReturn(Optional.of(uploadedFile));
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(author));
		when(pinWriter.create(42L, PinType.question, 37.4979, 127.0276)).thenReturn(100L);
		when(questionRepository.save(any(Question.class))).thenAnswer(invocation -> {
			Question question = invocation.getArgument(0);
			setId(question, 200L);
			return question;
		});

		QuestionDetailResponse response = service.create(
			principal(),
			new QuestionCreateRequest(
				"title",
				"content",
				new QuestionLocation(37.4979, 127.0276),
				List.of(imageId)
			)
		);

		assertThat(response.questionId()).isEqualTo(200L);
		assertThat(response.title()).isEqualTo("title");
		assertThat(response.author().profileImageUrl()).isEqualTo("/api/v1/files/%s".formatted(profileId));
		assertThat(response.imageUrls()).containsExactly("/api/v1/files/%s?v=display".formatted(imageId));
		InOrder inOrder = inOrder(fileRepository, pinWriter, questionRepository, questionImageRepository);
		inOrder.verify(fileRepository).findByFileIdAndUploaderId(imageId, 42L);
		inOrder.verify(pinWriter).create(42L, PinType.question, 37.4979, 127.0276);
		inOrder.verify(questionRepository).save(any(Question.class));
		inOrder.verify(questionImageRepository).saveAll(any());
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
				new QuestionLocation(37.4979, 127.0276),
				List.of(imageId)
			)
		)).isInstanceOf(InvalidQuestionRequestException.class)
			.hasMessage("Invalid image");

		verify(pinWriter, never()).create(any(), any(), any(Double.class), any(Double.class));
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
		assertThat(response.imageUrls()).containsExactly(
			"/api/v1/files/%s?v=display".formatted(first),
			"/api/v1/files/%s?v=display".formatted(second)
		);
		assertThat(response.author().profileImageUrl()).isEqualTo("/api/v1/files/%s".formatted(profileId));
		assertThat(response.answers()).isEmpty();
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
		assertThat(page.items().get(0).thumbnailUrl()).isEqualTo("/api/v1/files/%s?v=thumb".formatted(thumbnail));
		assertThat(page.nextCursor()).isEqualTo("MjAw");
	}

	@Test
	void listMineUsesDecodedCursorQueryWhenCursorIsPresent() {
		when(questionRepository.findMineAfterCursor(42L, 200L, 2)).thenReturn(List.of(
			new MineProjection(100L, "older", false, null, 0, Instant.parse("2026-07-06T10:00:00Z"))
		));

		var page = service.listMine(principal(), "MjAw", 1);

		assertThat(page.items()).hasSize(1);
		assertThat(page.items().get(0).questionId()).isEqualTo(100L);
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void updateRejectsNonAuthorBeforeChangingImages() {
		Question question = Question.create(100L, 99L, "title", "content");
		setId(question, 200L);
		when(questionRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(question));

		assertThatThrownBy(() -> service.update(
			principal(),
			200L,
			new QuestionUpdateRequest("updated", null, List.of())
		)).isInstanceOf(QuestionForbiddenException.class);

		verify(questionImageRepository, never()).deleteByQuestionId(200L);
	}

	@Test
	void updateReplacesImagesAndSchedulesRemovedImageCleanup() {
		UUID oldImage = UUID.fromString("00000000-0000-0000-0000-000000000031");
		UUID newImage = UUID.fromString("00000000-0000-0000-0000-000000000032");
		UUID profileId = UUID.fromString("00000000-0000-0000-0000-000000000103");
		Question question = Question.create(100L, 42L, "title", "content");
		setId(question, 200L);
		when(questionRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(question));
		when(questionImageRepository.findByQuestionIdOrderBySortOrderAsc(200L))
			.thenReturn(List.of(QuestionImage.link(200L, oldImage, 0)));
		when(fileRepository.findByFileIdAndUploaderId(newImage, 42L)).thenReturn(Optional.of(uploadedFile(newImage, 42L)));
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(user(profileId)));

		TransactionSynchronizationManager.initSynchronization();
		QuestionDetailResponse response;
		try {
			response = service.update(
				principal(),
				200L,
				new QuestionUpdateRequest("updated", "changed", List.of(newImage))
			);

			verify(imageCleanupService, never()).cleanRemovedImagesAfterCommit(any());
			TransactionSynchronizationManager.getSynchronizations()
				.forEach(synchronization -> synchronization.afterCommit());
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		assertThat(response.title()).isEqualTo("updated");
		assertThat(response.content()).isEqualTo("changed");
		assertThat(response.author().profileImageUrl()).isEqualTo("/api/v1/files/%s".formatted(profileId));
		verify(questionImageRepository).deleteByQuestionId(200L);
		verify(questionImageRepository).saveAll(any());
		verify(imageCleanupService).cleanRemovedImagesAfterCommit(List.of(oldImage));
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
