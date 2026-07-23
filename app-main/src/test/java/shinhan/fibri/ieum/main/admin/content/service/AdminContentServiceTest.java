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
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import shinhan.fibri.ieum.main.admin.audit.repository.AdminAuditLogWriter;
import shinhan.fibri.ieum.main.admin.content.exception.ContentNotFoundException;
import shinhan.fibri.ieum.main.admin.content.exception.UnsupportedContentTypeException;
import shinhan.fibri.ieum.main.admin.content.repository.AdminContentHardDeleteRepository;
import shinhan.fibri.ieum.main.ai.question.repository.QuestionAnswerTicketWriter;
import shinhan.fibri.ieum.main.file.service.S3FileDeletionService;
import shinhan.fibri.ieum.main.pin.repository.PinWriter;
import shinhan.fibri.ieum.main.question.domain.Question;
import shinhan.fibri.ieum.main.question.repository.QuestionDeletionState;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;
import shinhan.fibri.ieum.main.question.service.QuestionDeletionExecutor;

class AdminContentServiceTest {

	private final QuestionRepository questionRepository = mock(QuestionRepository.class);
	private final PinWriter pinWriter = mock(PinWriter.class);
	private final QuestionAnswerTicketWriter questionAnswerTicketWriter = mock(QuestionAnswerTicketWriter.class);
	private final QuestionDeletionExecutor questionDeletionExecutor = new QuestionDeletionExecutor(
		questionRepository,
		pinWriter,
		questionAnswerTicketWriter
	);
	private final AdminContentService service = new AdminContentService(
		questionDeletionExecutor,
		mock(AdminContentHardDeleteRepository.class),
		questionAnswerTicketWriter,
		mock(AdminAuditLogWriter.class),
		mock(S3FileDeletionService.class),
		mock(Executor.class)
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
}
