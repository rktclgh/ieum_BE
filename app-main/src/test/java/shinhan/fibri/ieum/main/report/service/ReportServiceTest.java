package shinhan.fibri.ieum.main.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.report.domain.Report;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.main.report.dto.CreateReportRequest;
import shinhan.fibri.ieum.main.report.exception.ReportMessageNotFoundException;
import shinhan.fibri.ieum.main.report.repository.ReportRepository;

class ReportServiceTest {

	private final MessageRepository messageRepository = org.mockito.Mockito.mock(MessageRepository.class);
	private final ChatMemberRepository chatMemberRepository = org.mockito.Mockito.mock(ChatMemberRepository.class);
	private final ReportRepository reportRepository = org.mockito.Mockito.mock(ReportRepository.class);
	private final UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);
	private final ReportEventPublisher reportEventPublisher = org.mockito.Mockito.mock(ReportEventPublisher.class);
	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
	private final ReportService service = new ReportService(
		messageRepository,
		chatMemberRepository,
		reportRepository,
		userRepository,
		reportEventPublisher,
		objectMapper
	);

	@Test
	void createMessageReportStoresReportedMessageAndTwentyBeforeAfterContext() throws Exception {
		User reporter = user(42L, "reporter@example.com", "reporter");
		User reported = user(77L, "reported@example.com", "reported");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		Message reportedMessage = message(500L, room, reported, "reported-message", "2026-07-09T10:00:00+09:00");
		Message before = message(499L, room, reporter, "before", "2026-07-09T09:59:00+09:00");
		Message after = message(501L, room, reporter, "after", "2026-07-09T10:01:00+09:00");
		when(messageRepository.findById(500L)).thenReturn(Optional.of(reportedMessage));
		when(chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(100L, 42L)).thenReturn(true);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(reporter));
		when(messageRepository.findContextBeforeMessage(100L, reportedMessage.getCreatedAt(), 500L, Pageable.ofSize(20)))
			.thenReturn(List.of(before));
		when(messageRepository.findContextAfterMessage(100L, reportedMessage.getCreatedAt(), 500L, Pageable.ofSize(20)))
			.thenReturn(List.of(after));
		when(reportRepository.save(any(Report.class))).thenAnswer(invocation -> {
			Report report = invocation.getArgument(0);
			setField(report, "id", 900L);
			return report;
		});

		var response = service.create(principal(42L), new CreateReportRequest(
			500L,
			ReportReason.abuse,
			"욕설과 공격적인 표현"
		));

		assertThat(response.reportId()).isEqualTo(900L);
		ArgumentCaptor<Report> reportCaptor = ArgumentCaptor.forClass(Report.class);
		verify(reportRepository).save(reportCaptor.capture());
		Report saved = reportCaptor.getValue();
		assertThat(saved.getReporter().getId()).isEqualTo(42L);
		assertThat(saved.getMessage().getId()).isEqualTo(500L);
		assertThat(saved.getReportedUser().getId()).isEqualTo(77L);
		assertThat(saved.getReason()).isEqualTo(ReportReason.abuse);
		assertThat(saved.getDetail()).isEqualTo("욕설과 공격적인 표현");

		var snapshot = objectMapper.readTree(saved.getContextSnapshot());
		assertThat(snapshot.get("roomId").asLong()).isEqualTo(100L);
		assertThat(snapshot.get("reported").get("messageId").asLong()).isEqualTo(500L);
		assertThat(snapshot.get("before").get(0).get("messageId").asLong()).isEqualTo(499L);
		assertThat(snapshot.get("after").get(0).get("messageId").asLong()).isEqualTo(501L);
		ArgumentCaptor<ReportCreatedEvent> eventCaptor = ArgumentCaptor.forClass(ReportCreatedEvent.class);
		verify(reportEventPublisher).reportCreated(eventCaptor.capture());
		assertThat(eventCaptor.getValue().reportId()).isEqualTo(900L);
	}

	@Test
	void createMessageReportPublishesEventAfterCommitWhenTransactionSynchronizationIsActive() {
		User reporter = user(42L, "reporter@example.com", "reporter");
		User reported = user(77L, "reported@example.com", "reported");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		Message reportedMessage = message(500L, room, reported, "reported-message", "2026-07-09T10:00:00+09:00");
		when(messageRepository.findById(500L)).thenReturn(Optional.of(reportedMessage));
		when(chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(100L, 42L)).thenReturn(true);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(reporter));
		when(messageRepository.findContextBeforeMessage(100L, reportedMessage.getCreatedAt(), 500L, Pageable.ofSize(20)))
			.thenReturn(List.of());
		when(messageRepository.findContextAfterMessage(100L, reportedMessage.getCreatedAt(), 500L, Pageable.ofSize(20)))
			.thenReturn(List.of());
		when(reportRepository.save(any(Report.class))).thenAnswer(invocation -> {
			Report report = invocation.getArgument(0);
			setField(report, "id", 900L);
			return report;
		});

		TransactionSynchronizationManager.initSynchronization();
		try {
			service.create(principal(42L), new CreateReportRequest(500L, ReportReason.abuse, null));

			verifyNoInteractions(reportEventPublisher);

			TransactionSynchronizationManager.getSynchronizations()
				.forEach(TransactionSynchronization::afterCommit);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		verify(reportEventPublisher).reportCreated(any(ReportCreatedEvent.class));
	}

	@Test
	void createMessageReportRequiresReporterToBeActiveRoomMember() {
		User reporter = user(42L, "reporter@example.com", "reporter");
		User reported = user(77L, "reported@example.com", "reported");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		Message reportedMessage = message(500L, room, reported, "reported-message", "2026-07-09T10:00:00+09:00");
		when(messageRepository.findById(500L)).thenReturn(Optional.of(reportedMessage));
		when(chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(100L, 42L)).thenReturn(false);

		assertThatThrownBy(() -> service.create(principal(42L), new CreateReportRequest(500L, ReportReason.spam, null)))
			.isInstanceOf(NotRoomMemberException.class);

		verifyNoInteractions(reportRepository);
	}

	@Test
	void createMessageReportRejectsMissingOrDeletedMessage() {
		when(messageRepository.findById(500L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.create(principal(42L), new CreateReportRequest(500L, ReportReason.spam, null)))
			.isInstanceOf(ReportMessageNotFoundException.class);

		verifyNoInteractions(reportRepository);
	}

	@Test
	void createMessageReportRejectsDeletedMessageWithoutSavingReport() {
		User reporter = user(42L, "reporter@example.com", "reporter");
		User reported = user(77L, "reported@example.com", "reported");
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		Message deletedMessage = message(500L, room, reported, "deleted-message", "2026-07-09T10:00:00+09:00");
		deletedMessage.markDeleted(OffsetDateTime.parse("2026-07-09T10:02:00+09:00"));
		when(messageRepository.findById(500L)).thenReturn(Optional.of(deletedMessage));

		assertThatThrownBy(() -> service.create(principal(42L), new CreateReportRequest(500L, ReportReason.spam, null)))
			.isInstanceOf(ReportMessageNotFoundException.class);

		verifyNoInteractions(reportRepository);
	}

	private AuthenticatedUser principal(Long userId) {
		return new AuthenticatedUser(userId, "user" + userId + "@example.com", UserRole.user, UserStatus.active);
	}

	private User user(Long id, String email, String nickname) {
		User user = User.createEmailUser(
			email,
			"hash",
			nickname,
			LocalDate.of(1995, 1, 1),
			GenderType.female,
			"KR"
		);
		setField(user, "id", id);
		return user;
	}

	private ChatRoom room(ChatRoom room, Long id) {
		setField(room, "id", id);
		return room;
	}

	private Message message(Long id, ChatRoom room, User sender, String content, String createdAt) {
		Message message = Message.text(room, sender, content, OffsetDateTime.parse(createdAt));
		setField(message, "id", id);
		return message;
	}

	private void setField(Object target, String fieldName, Object value) {
		try {
			java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
