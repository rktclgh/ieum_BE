package shinhan.fibri.ieum.main.admin.content.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.audit.domain.AdminAuditAction;
import shinhan.fibri.ieum.main.admin.audit.repository.AdminAuditLogWriter;
import shinhan.fibri.ieum.main.admin.content.domain.AdminContentType;
import shinhan.fibri.ieum.main.admin.content.exception.HardDeleteConfirmationMismatchException;
import shinhan.fibri.ieum.main.admin.content.repository.AdminContentFileCleanupTaskRepository;
import shinhan.fibri.ieum.main.admin.content.repository.AdminContentHardDeleteRepository;
import shinhan.fibri.ieum.main.admin.content.repository.AdminContentHardDeleteResult;
import shinhan.fibri.ieum.main.admin.content.repository.AdminContentHardDeleteTarget;
import shinhan.fibri.ieum.main.ai.question.repository.QuestionAnswerTicketWriter;
import shinhan.fibri.ieum.main.question.service.QuestionDeletionExecutor;

class AdminContentHardDeleteServiceTest {

	private final QuestionDeletionExecutor questionDeletionExecutor = mock(QuestionDeletionExecutor.class);
	private final AdminContentHardDeleteRepository hardDeleteRepository = mock(AdminContentHardDeleteRepository.class);
	private final QuestionAnswerTicketWriter questionAnswerTicketWriter = mock(QuestionAnswerTicketWriter.class);
	private final AdminAuditLogWriter auditLogWriter = mock(AdminAuditLogWriter.class);
	private final AdminContentFileCleanupTaskRepository fileCleanupTaskRepository = mock(AdminContentFileCleanupTaskRepository.class);
	private final AdminContentService service = new AdminContentService(
		questionDeletionExecutor,
		hardDeleteRepository,
		questionAnswerTicketWriter,
		auditLogWriter,
		fileCleanupTaskRepository
	);

	@Test
	void hardDeleteRejectsATokenThatDoesNotMatchTheTarget() {
		assertThatThrownBy(() -> service.hardDelete(admin(), "question", 42L, "DELETE QUESTION 41"))
			.isInstanceOf(HardDeleteConfirmationMismatchException.class);

		verify(hardDeleteRepository, never()).findForHardDelete(AdminContentType.QUESTION, 42L);
		verify(hardDeleteRepository, never()).hardDelete(any(), any());
		verify(questionAnswerTicketWriter, never()).requestCancellation(42L);
		verify(fileCleanupTaskRepository, never()).enqueue(any());
	}

	@Test
	void hardDeleteCancelsQuestionAiWorkWritesAuditAndEnqueuesCleanupTask() {
		AdminContentHardDeleteTarget target = target(AdminContentType.QUESTION, 42L, 420L, true);
		when(hardDeleteRepository.findForHardDelete(AdminContentType.QUESTION, 42L)).thenReturn(Optional.of(target));
		when(hardDeleteRepository.hardDelete(AdminContentType.QUESTION, target))
			.thenReturn(new AdminContentHardDeleteResult(List.of("final/question/42/original.jpg")));

		service.hardDelete(admin(), "question", 42L, "DELETE QUESTION 42");

		InOrder inOrder = inOrder(questionAnswerTicketWriter, hardDeleteRepository, fileCleanupTaskRepository, auditLogWriter);
		inOrder.verify(questionAnswerTicketWriter).requestCancellation(42L);
		inOrder.verify(hardDeleteRepository).findForHardDelete(AdminContentType.QUESTION, 42L);
		inOrder.verify(hardDeleteRepository).hardDelete(AdminContentType.QUESTION, target);
		inOrder.verify(fileCleanupTaskRepository).enqueue(List.of("final/question/42/original.jpg"));
		inOrder.verify(auditLogWriter).append(
			eq(1L),
			eq(AdminAuditAction.QUESTION_HARD_DELETED),
			eq("question"),
			eq(42L),
			anyMap()
		);
	}

	@Test
	void hardDeleteMeetingWritesMeetingAuditWithoutQuestionCancellation() {
		AdminContentHardDeleteTarget target = target(AdminContentType.MEETING, 7L, 700L, false);
		when(hardDeleteRepository.findForHardDelete(AdminContentType.MEETING, 7L)).thenReturn(Optional.of(target));
		when(hardDeleteRepository.hardDelete(AdminContentType.MEETING, target))
			.thenReturn(new AdminContentHardDeleteResult(List.of()));

		service.hardDelete(admin(), "meeting", 7L, "DELETE MEETING 7");

		verify(questionAnswerTicketWriter, never()).requestCancellation(7L);
		verify(auditLogWriter).append(
			eq(1L),
			eq(AdminAuditAction.MEETING_HARD_DELETED),
			eq("meeting"),
			eq(7L),
			anyMap()
		);
	}

	private static AuthenticatedUser admin() {
		return new AuthenticatedUser(1L, "admin@example.com", UserRole.admin, UserStatus.active);
	}

	private static AdminContentHardDeleteTarget target(
		AdminContentType type,
		Long contentId,
		Long pinId,
		boolean softDeleted
	) {
		return new AdminContentHardDeleteTarget(
			type,
			contentId,
			pinId,
			"title",
			"author",
			2L,
			OffsetDateTime.parse("2026-07-01T00:00:00Z"),
			softDeleted ? OffsetDateTime.parse("2026-07-02T00:00:00Z") : null
		);
	}

}
