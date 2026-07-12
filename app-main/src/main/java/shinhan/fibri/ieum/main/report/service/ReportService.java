package shinhan.fibri.ieum.main.report.service;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.report.domain.Report;
import shinhan.fibri.ieum.main.report.domain.ReportContextSnapshot;
import shinhan.fibri.ieum.main.report.dto.CreateReportRequest;
import shinhan.fibri.ieum.main.report.dto.CreateReportResponse;
import shinhan.fibri.ieum.main.report.exception.ReportMessageNotFoundException;
import shinhan.fibri.ieum.main.report.repository.ReportRepository;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

@Service
public class ReportService {

	private static final int CONTEXT_LIMIT = 20;

	private final MessageRepository messageRepository;
	private final ChatMemberRepository chatMemberRepository;
	private final ReportRepository reportRepository;
	private final UserRepository userRepository;
	private final ReportContextSnapshotFactory snapshotFactory;

	public ReportService(
		MessageRepository messageRepository,
		ChatMemberRepository chatMemberRepository,
		ReportRepository reportRepository,
		UserRepository userRepository,
		ReportContextSnapshotFactory snapshotFactory
	) {
		this.messageRepository = messageRepository;
		this.chatMemberRepository = chatMemberRepository;
		this.reportRepository = reportRepository;
		this.userRepository = userRepository;
		this.snapshotFactory = snapshotFactory;
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
		return new CreateReportResponse(report.getId());
	}

}
