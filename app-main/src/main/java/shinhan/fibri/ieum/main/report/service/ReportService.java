package shinhan.fibri.ieum.main.report.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;
import shinhan.fibri.ieum.main.answer.domain.Answer;
import shinhan.fibri.ieum.main.answer.domain.AnswerImage;
import shinhan.fibri.ieum.main.answer.exception.AnswerNotFoundException;
import shinhan.fibri.ieum.main.answer.repository.AnswerImageRepository;
import shinhan.fibri.ieum.main.answer.repository.AnswerRepository;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.report.domain.Report;
import shinhan.fibri.ieum.main.report.domain.ReportContextSnapshot;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.main.report.dto.CreateReportRequest;
import shinhan.fibri.ieum.main.report.dto.CreateReportResponse;
import shinhan.fibri.ieum.main.report.exception.ReportMessageNotFoundException;
import shinhan.fibri.ieum.main.report.repository.ReportRepository;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

@Service
public class ReportService {

	private static final int CONTEXT_LIMIT = 20;
	private static final Logger log = LoggerFactory.getLogger(ReportService.class);

	private final MessageRepository messageRepository;
	private final ChatMemberRepository chatMemberRepository;
	private final ReportRepository reportRepository;
	private final UserRepository userRepository;
	private final ReportContextSnapshotFactory snapshotFactory;
	private final AnswerRepository answerRepository;
	private final AnswerImageRepository answerImageRepository;
	private final QuestionRepository questionRepository;

	public ReportService(
		MessageRepository messageRepository,
		ChatMemberRepository chatMemberRepository,
		ReportRepository reportRepository,
		UserRepository userRepository,
		ReportContextSnapshotFactory snapshotFactory,
		AnswerRepository answerRepository,
		AnswerImageRepository answerImageRepository,
		QuestionRepository questionRepository
	) {
		this.messageRepository = messageRepository;
		this.chatMemberRepository = chatMemberRepository;
		this.reportRepository = reportRepository;
		this.userRepository = userRepository;
		this.snapshotFactory = snapshotFactory;
		this.answerRepository = answerRepository;
		this.answerImageRepository = answerImageRepository;
		this.questionRepository = questionRepository;
	}

	@Transactional
	public CreateReportResponse create(AuthenticatedUser principal, CreateReportRequest request) {
		Message reportedMessage = messageRepository.findById(request.messageId())
			.filter(message -> message.getDeletedAt() == null)
			.orElseThrow(ReportMessageNotFoundException::new);
		Long roomId = reportedMessage.getRoom().getId();
		if (!chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(roomId, principal.userId())) {
			throw new NotRoomMemberException();
		}
		User reporter = userRepository.findByIdAndDeletedAtIsNull(principal.userId())
			.orElseThrow(UserNotFoundException::new);

		List<Message> before = messageRepository.findContextBeforeMessage(
			roomId,
			reportedMessage.getCreatedAt(),
			reportedMessage.getId(),
			Pageable.ofSize(CONTEXT_LIMIT)
		);
		List<Message> after = messageRepository.findContextAfterMessage(
			roomId,
			reportedMessage.getCreatedAt(),
			reportedMessage.getId(),
			Pageable.ofSize(CONTEXT_LIMIT)
		);

		ReportContextSnapshot contextSnapshot = snapshotFactory.create(roomId, before, reportedMessage, after);
		Report report = reportRepository.save(Report.messageReport(
			reporter,
			reportedMessage,
			request.reason(),
			request.detail(),
			contextSnapshot
		));
		logReportReceived(report);
		return new CreateReportResponse(report.getId());
	}

	@Transactional
	public CreateReportResponse createAnswer(
		AuthenticatedUser principal,
		Long answerId,
		ReportReason reason,
		String detail
	) {
		Answer answer = answerRepository.findById(answerId)
			.orElseThrow(AnswerNotFoundException::new);
		questionRepository.findActiveByIdForShare(answer.getQuestionId())
			.orElseThrow(AnswerNotFoundException::new);
		User reporter = userRepository.findByIdAndDeletedAtIsNull(principal.userId())
			.orElseThrow(UserNotFoundException::new);
		User reportedUser = answer.isAi() ? null : userRepository.getReferenceById(answer.getAuthorId());
		List<AnswerImage> images = answerImageRepository.findByAnswerIdInOrderBySortOrderAsc(List.of(answerId));
		ReportContextSnapshot contextSnapshot = snapshotFactory.createAnswer(answer, images);
		Report report = reportRepository.save(Report.answerReport(
			reporter,
			answer,
			reportedUser,
			reason,
			detail,
			contextSnapshot
		));
		logReportReceived(report);
		return new CreateReportResponse(report.getId());
	}

	private void logReportReceived(Report report) {
		log.info(
			"event=report_received reportId={} targetType={} aiReviewState={}",
			report.getId(), report.getTargetType(), report.getAiReviewState()
		);
	}

}
