package shinhan.fibri.ieum.main.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.report.domain.Report;
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
	private final ReportEventPublisher reportEventPublisher;
	private final ObjectMapper objectMapper;

	public ReportService(
		MessageRepository messageRepository,
		ChatMemberRepository chatMemberRepository,
		ReportRepository reportRepository,
		UserRepository userRepository,
		ReportEventPublisher reportEventPublisher,
		ObjectMapper objectMapper
	) {
		this.messageRepository = messageRepository;
		this.chatMemberRepository = chatMemberRepository;
		this.reportRepository = reportRepository;
		this.userRepository = userRepository;
		this.reportEventPublisher = reportEventPublisher;
		this.objectMapper = objectMapper;
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

		String contextSnapshot = contextSnapshot(roomId, before, reportedMessage, after);
		Report report = reportRepository.save(Report.messageReport(
			reporter,
			reportedMessage,
			request.reason(),
			request.detail(),
			contextSnapshot
		));
		publishAfterCommit(new ReportCreatedEvent(report.getId()));
		return new CreateReportResponse(report.getId());
	}

	private void publishAfterCommit(ReportCreatedEvent event) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			reportEventPublisher.reportCreated(event);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				reportEventPublisher.reportCreated(event);
			}
		});
	}

	private String contextSnapshot(Long roomId, List<Message> before, Message reported, List<Message> after) {
		ReportContextSnapshot snapshot = new ReportContextSnapshot(
			roomId,
			before.stream().map(ReportContextMessage::from).toList(),
			ReportContextMessage.from(reported),
			after.stream().map(ReportContextMessage::from).toList()
		);
		try {
			return objectMapper.writeValueAsString(snapshot);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize report context snapshot", exception);
		}
	}

	private record ReportContextSnapshot(
		Long roomId,
		List<ReportContextMessage> before,
		ReportContextMessage reported,
		List<ReportContextMessage> after
	) {
	}

	private record ReportContextMessage(
		Long messageId,
		Long senderId,
		String senderNickname,
		String content,
		String imageFileId,
		OffsetDateTime createdAt
	) {

		private static ReportContextMessage from(Message message) {
			return new ReportContextMessage(
				message.getId(),
				message.getSender().getId(),
				message.getSender().getNickname(),
				message.getContent(),
				message.getImageFileId() == null ? null : message.getImageFileId().toString(),
				message.getCreatedAt()
			);
		}
	}
}
