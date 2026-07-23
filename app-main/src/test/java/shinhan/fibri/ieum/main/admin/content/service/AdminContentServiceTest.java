package shinhan.fibri.ieum.main.admin.content.service;

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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.audit.domain.AdminAuditAction;
import shinhan.fibri.ieum.main.admin.audit.repository.AdminAuditLogWriter;
import shinhan.fibri.ieum.main.admin.content.domain.AdminContentType;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentDetailResponse;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentListItem;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentListRequest;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentListResponse;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentUpdateRequest;
import shinhan.fibri.ieum.main.admin.content.exception.ContentNotFoundException;
import shinhan.fibri.ieum.main.admin.content.exception.UnsupportedContentTypeException;
import shinhan.fibri.ieum.main.admin.content.repository.AdminContentFileCleanupTaskRepository;
import shinhan.fibri.ieum.main.admin.content.repository.AdminContentHardDeleteRepository;
import shinhan.fibri.ieum.main.admin.content.repository.AdminContentQueryRepository;
import shinhan.fibri.ieum.main.ai.question.repository.QuestionAnswerTicketWriter;
import shinhan.fibri.ieum.main.pin.repository.PinWriter;
import shinhan.fibri.ieum.main.question.domain.Question;
import shinhan.fibri.ieum.main.question.repository.QuestionDeletionState;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;
import shinhan.fibri.ieum.main.question.service.QuestionDeletionExecutor;

class AdminContentServiceTest {

	private final QuestionRepository questionRepository = mock(QuestionRepository.class);
	private final PinWriter pinWriter = mock(PinWriter.class);
	private final QuestionAnswerTicketWriter questionAnswerTicketWriter = mock(QuestionAnswerTicketWriter.class);
	private final AdminAuditLogWriter auditLogWriter = mock(AdminAuditLogWriter.class);
	private final AdminContentQueryRepository contentQueryRepository = mock(AdminContentQueryRepository.class);
	private final QuestionDeletionExecutor questionDeletionExecutor = new QuestionDeletionExecutor(
		questionRepository,
		pinWriter,
		questionAnswerTicketWriter
	);
	private final AdminContentService service = new AdminContentService(
		questionDeletionExecutor,
		mock(AdminContentHardDeleteRepository.class),
		questionAnswerTicketWriter,
		auditLogWriter,
		mock(AdminContentFileCleanupTaskRepository.class),
		contentQueryRepository
	);

	@Test
	void hideQuestionCancelsAiThenSoftDeletesQuestionAndPinAtSameTimeWithoutAuthorCheck() {
		Question question = Question.create(100L, 42L, "title", "content");
		setId(question, 200L);
		QuestionDeletionState deletionState = deletionState(99L, null);
		when(questionRepository.findDeletionState(200L)).thenReturn(Optional.of(deletionState));
		when(questionRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(question));

		service.hide("question", 200L);

		InOrder inOrder = inOrder(questionRepository, questionAnswerTicketWriter, pinWriter);
		inOrder.verify(questionRepository).findDeletionState(200L);
		inOrder.verify(questionAnswerTicketWriter).requestCancellation(200L);
		inOrder.verify(questionRepository).findByIdForUpdate(200L);
		ArgumentCaptor<OffsetDateTime> deletedAt = ArgumentCaptor.forClass(OffsetDateTime.class);
		inOrder.verify(pinWriter).softDelete(eq(100L), deletedAt.capture());
		assertThat(question.getDeletedAt()).isEqualTo(deletedAt.getValue());
	}

	@Test
	void hideQuestionIsIdempotentWhenAlreadyDeleted() {
		QuestionDeletionState deletionState = deletionState(42L, Instant.parse("2026-07-13T10:00:00Z"));
		when(questionRepository.findDeletionState(200L))
			.thenReturn(Optional.of(deletionState));

		service.hide("question", 200L);

		verify(questionAnswerTicketWriter, never()).requestCancellation(any());
		verify(questionRepository, never()).findByIdForUpdate(any());
		verify(pinWriter, never()).softDelete(any(), any());
	}

	@Test
	void hideQuestionThrowsNotFoundWhenMissing() {
		when(questionRepository.findDeletionState(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.hide("question", 999L))
			.isInstanceOf(ContentNotFoundException.class);

		verify(questionAnswerTicketWriter, never()).requestCancellation(any());
	}

	@Test
	void hideQuestionAcceptsConcurrentDeleteAfterPrecheck() {
		QuestionDeletionState deletionState = deletionState(42L, null);
		when(questionRepository.findDeletionState(200L)).thenReturn(Optional.of(deletionState));
		when(questionRepository.findByIdForUpdate(200L)).thenReturn(Optional.empty());

		service.hide("question", 200L);

		verify(questionAnswerTicketWriter).requestCancellation(200L);
		verify(pinWriter, never()).softDelete(any(), any());
	}

	@Test
	void unsupportedTypeFailsBeforeRepositoryAccess() {
		assertThatThrownBy(() -> service.hide("meeting", 100L))
			.isInstanceOf(UnsupportedContentTypeException.class);

		verify(questionRepository, never()).findDeletionState(any());
	}

	@Test
	void getQuestionsFetchesOneExtraRowAndOnlyReturnsNextCursorWhenMoreRowsExist() {
		when(contentQueryRepository.findQuestions(50L, 3)).thenReturn(List.of(
			listItem("question", 49L),
			listItem("question", 48L),
			listItem("question", 47L)
		));
		when(contentQueryRepository.findMeetings(null, 3)).thenReturn(List.of(
			listItem("meeting", 10L),
			listItem("meeting", 9L)
		));

		AdminContentListResponse questions = service.getQuestions(new AdminContentListRequest("50", 2));
		AdminContentListResponse meetings = service.getMeetings(new AdminContentListRequest(null, 2));

		assertThat(questions.items()).extracting(AdminContentListItem::contentId).containsExactly(49L, 48L);
		assertThat(questions.nextCursor()).isEqualTo("48");
		assertThat(meetings.items()).extracting(AdminContentListItem::contentId).containsExactly(10L, 9L);
		assertThat(meetings.nextCursor()).isNull();
	}

	@Test
	void updateQuestionCancelsAiWorkBeforeLockingAndWritesAudit() {
		AdminContentDetailResponse before = detail("question", 42L, "old title", "old content");
		AdminContentDetailResponse after = detail("question", 42L, "new title", "new content");
		when(contentQueryRepository.lockDetail(AdminContentType.QUESTION, 42L)).thenReturn(Optional.of(before));
		when(contentQueryRepository.findDetail(AdminContentType.QUESTION, 42L)).thenReturn(Optional.of(after));

		AdminContentDetailResponse result = service.update(
			admin(),
			"question",
			42L,
			new AdminContentUpdateRequest("new title", "new content")
		);

		assertThat(result).isEqualTo(after);
		InOrder inOrder = inOrder(questionAnswerTicketWriter, contentQueryRepository, auditLogWriter);
		inOrder.verify(questionAnswerTicketWriter).requestCancellation(42L);
		inOrder.verify(contentQueryRepository).lockDetail(AdminContentType.QUESTION, 42L);
		inOrder.verify(contentQueryRepository).update(AdminContentType.QUESTION, 42L, "new title", "new content");
		inOrder.verify(auditLogWriter).append(
			eq(1L),
			eq(AdminAuditAction.QUESTION_UPDATED),
			eq("question"),
			eq(42L),
			eq(java.util.Map.of(
				"previousTitle", "old title",
				"newTitle", "new title",
				"previousContentLength", 11,
				"newContentLength", 11
			))
		);
	}

	@Test
	void updateMeetingDoesNotCancelQuestionAiWork() {
		AdminContentDetailResponse before = detail("meeting", 7L, "old meeting", "old content");
		AdminContentDetailResponse after = detail("meeting", 7L, "new meeting", "new content");
		when(contentQueryRepository.lockDetail(AdminContentType.MEETING, 7L)).thenReturn(Optional.of(before));
		when(contentQueryRepository.findDetail(AdminContentType.MEETING, 7L)).thenReturn(Optional.of(after));

		AdminContentDetailResponse result = service.update(
			admin(),
			"meeting",
			7L,
			new AdminContentUpdateRequest("new meeting", "new content")
		);

		assertThat(result).isEqualTo(after);
		verify(questionAnswerTicketWriter, never()).requestCancellation(7L);
		verify(auditLogWriter).append(
			eq(1L),
			eq(AdminAuditAction.MEETING_UPDATED),
			eq("meeting"),
			eq(7L),
			eq(java.util.Map.of(
				"previousTitle", "old meeting",
				"newTitle", "new meeting",
				"previousContentLength", 11,
				"newContentLength", 11
			))
		);
	}

	private QuestionDeletionState deletionState(Long authorId, Instant deletedAt) {
		return new QuestionDeletionState() {
			@Override
			public Long getAuthorId() {
				return authorId;
			}

			@Override
			public Instant getDeletedAt() {
				return deletedAt;
			}
		};
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

	private static AuthenticatedUser admin() {
		return new AuthenticatedUser(1L, "admin@example.com", UserRole.admin, UserStatus.active);
	}

	private static AdminContentDetailResponse detail(String type, Long id, String title, String content) {
		return new AdminContentDetailResponse(
			type,
			id,
			title,
			content,
			"author",
			2L,
			OffsetDateTime.parse("2026-07-01T00:00:00Z"),
			OffsetDateTime.parse("2026-07-02T00:00:00Z"),
			null,
			"question".equals(type) ? false : null,
			"meeting".equals(type) ? "open" : null,
			"meeting".equals(type) ? 1 : null
		);
	}

	private static AdminContentListItem listItem(String type, Long id) {
		return new AdminContentListItem(
			type,
			id,
			type + " title",
			"author",
			2L,
			OffsetDateTime.parse("2026-07-01T00:00:00Z"),
			OffsetDateTime.parse("2026-07-02T00:00:00Z"),
			null,
			"question".equals(type) ? false : null,
			"meeting".equals(type) ? "open" : null,
			"meeting".equals(type) ? 1 : null
		);
	}
}
