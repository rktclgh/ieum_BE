package shinhan.fibri.ieum.main.admin.content.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.audit.domain.AdminAuditAction;
import shinhan.fibri.ieum.main.admin.audit.repository.AdminAuditLogWriter;
import shinhan.fibri.ieum.main.admin.content.domain.AdminContentType;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentPreviewResponse;
import shinhan.fibri.ieum.main.admin.content.exception.ContentNotFoundException;
import shinhan.fibri.ieum.main.admin.content.exception.HardDeleteConfirmationMismatchException;
import shinhan.fibri.ieum.main.admin.content.exception.UnsupportedContentTypeException;
import shinhan.fibri.ieum.main.admin.content.repository.AdminContentHardDeleteRepository;
import shinhan.fibri.ieum.main.admin.content.repository.AdminContentHardDeleteResult;
import shinhan.fibri.ieum.main.admin.content.repository.AdminContentHardDeleteTarget;
import shinhan.fibri.ieum.main.ai.question.repository.QuestionAnswerTicketWriter;
import shinhan.fibri.ieum.main.file.service.S3FileDeletionService;
import shinhan.fibri.ieum.main.question.exception.QuestionForbiddenException;
import shinhan.fibri.ieum.main.question.service.QuestionDeletionExecutor;

@Service
public class AdminContentService {

	private static final Logger log = LoggerFactory.getLogger(AdminContentService.class);
	private static final String QUESTION_TYPE = "question";

	private final QuestionDeletionExecutor questionDeletionExecutor;
	private final AdminContentHardDeleteRepository hardDeleteRepository;
	private final QuestionAnswerTicketWriter questionAnswerTicketWriter;
	private final AdminAuditLogWriter auditLogWriter;
	private final S3FileDeletionService s3FileDeletionService;
	private final Executor cleanupExecutor;

	public AdminContentService(
		QuestionDeletionExecutor questionDeletionExecutor,
		AdminContentHardDeleteRepository hardDeleteRepository,
		QuestionAnswerTicketWriter questionAnswerTicketWriter,
		AdminAuditLogWriter auditLogWriter,
		S3FileDeletionService s3FileDeletionService,
		@Qualifier("fileCleanupTaskExecutor") Executor cleanupExecutor
	) {
		this.questionDeletionExecutor = questionDeletionExecutor;
		this.hardDeleteRepository = hardDeleteRepository;
		this.questionAnswerTicketWriter = questionAnswerTicketWriter;
		this.auditLogWriter = auditLogWriter;
		this.s3FileDeletionService = s3FileDeletionService;
		this.cleanupExecutor = cleanupExecutor;
	}

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

	@Transactional(readOnly = true)
	public AdminContentPreviewResponse preview(String type, Long id) {
		AdminContentType contentType = AdminContentType.fromPath(type);
		AdminContentHardDeleteTarget target = hardDeleteRepository.preview(contentType, id)
			.orElseThrow(ContentNotFoundException::new);
		return new AdminContentPreviewResponse(
			contentType.pathValue(),
			target.contentId(),
			target.title(),
			target.authorNickname(),
			target.authorId(),
			target.createdAt(),
			target.deletedAt()
		);
	}

	@Transactional
	public void hardDelete(AuthenticatedUser principal, String type, Long id, String confirmationToken) {
		AdminContentType contentType = AdminContentType.fromPath(type);
		if (!contentType.expectedConfirmationToken(id).equals(confirmationToken)) {
			throw new HardDeleteConfirmationMismatchException();
		}
		AdminContentHardDeleteTarget target = hardDeleteRepository.findForHardDelete(contentType, id)
			.orElseThrow(ContentNotFoundException::new);
		if (contentType == AdminContentType.QUESTION) {
			questionAnswerTicketWriter.requestCancellation(id);
		}

		log.info(
			"Admin hard deleting content. adminUserId={}, type={}, id={}",
			principal.userId(),
			contentType.pathValue(),
			id
		);
		AdminContentHardDeleteResult result = hardDeleteRepository.hardDelete(contentType, target);
		auditLogWriter.append(
			principal.userId(),
			auditAction(contentType),
			contentType.pathValue(),
			id,
			Map.of(
				"deletedFileCount", result.deletedFileCount(),
				"wasSoftDeleted", target.wasSoftDeleted()
			)
		);
		cleanupAfterCommit(contentType, id, result.s3Keys());
	}

	private AdminAuditAction auditAction(AdminContentType contentType) {
		return switch (contentType) {
			case QUESTION -> AdminAuditAction.QUESTION_HARD_DELETED;
			case MEETING -> AdminAuditAction.MEETING_HARD_DELETED;
		};
	}

	private void cleanupAfterCommit(AdminContentType contentType, Long id, List<String> s3Keys) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			scheduleCleanup(contentType, id, s3Keys);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				scheduleCleanup(contentType, id, s3Keys);
			}
		});
	}

	private void scheduleCleanup(AdminContentType contentType, Long id, List<String> s3Keys) {
		try {
			cleanupExecutor.execute(() -> deleteS3Objects(contentType, id, s3Keys));
		} catch (RejectedExecutionException exception) {
			log.error(
				"Failed to schedule hard-deleted content S3 cleanup. type={}, id={}, s3KeyCount={}",
				contentType.pathValue(),
				id,
				s3Keys.size(),
				exception
			);
		}
	}

	private void deleteS3Objects(AdminContentType contentType, Long id, List<String> s3Keys) {
		for (String s3Key : s3Keys) {
			try {
				s3FileDeletionService.deleteOriginAndVariantsLogOnly(s3Key);
			} catch (RuntimeException exception) {
				log.error("Failed to delete S3 object for hard-deleted content. type={}, id={}", contentType.pathValue(), id, exception);
			}
		}
	}
}
