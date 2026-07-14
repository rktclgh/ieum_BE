package shinhan.fibri.ieum.main.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
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
import shinhan.fibri.ieum.main.answer.domain.Answer;
import shinhan.fibri.ieum.main.answer.domain.AnswerImage;
import shinhan.fibri.ieum.main.answer.exception.AnswerNotFoundException;
import shinhan.fibri.ieum.main.answer.repository.AnswerImageRepository;
import shinhan.fibri.ieum.main.answer.repository.AnswerRepository;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.report.domain.Report;
import shinhan.fibri.ieum.main.report.domain.ReportAiReviewState;
import shinhan.fibri.ieum.main.report.domain.ReportContextSnapshot;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.main.report.domain.ReportTargetType;
import shinhan.fibri.ieum.main.report.dto.CreateReportRequest;
import shinhan.fibri.ieum.main.report.exception.ReportMessageNotFoundException;
import shinhan.fibri.ieum.main.report.repository.ReportRepository;
import shinhan.fibri.ieum.main.question.domain.Question;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;

class ReportServiceTest {

	private final MessageRepository messageRepository = org.mockito.Mockito.mock(MessageRepository.class);
	private final ChatMemberRepository chatMemberRepository = org.mockito.Mockito.mock(ChatMemberRepository.class);
	private final ReportRepository reportRepository = org.mockito.Mockito.mock(ReportRepository.class);
	private final UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);
	private final ReportContextSnapshotFactory snapshotFactory = org.mockito.Mockito.mock(ReportContextSnapshotFactory.class);
	private final AnswerRepository answerRepository = org.mockito.Mockito.mock(AnswerRepository.class);
	private final AnswerImageRepository answerImageRepository = org.mockito.Mockito.mock(AnswerImageRepository.class);
	private final QuestionRepository questionRepository = org.mockito.Mockito.mock(QuestionRepository.class);
	private final ReportService service = new ReportService(
		messageRepository,
		chatMemberRepository,
		reportRepository,
		userRepository,
		snapshotFactory,
		answerRepository,
		answerImageRepository,
		questionRepository
	);
	private final ReportContextSnapshot contextSnapshot = new ReportContextSnapshot(
		"{\"schemaVersion\":1,\"reported\":{\"messageId\":500}}",
		"a".repeat(64)
	);
	private final ReportContextSnapshot answerSnapshot = new ReportContextSnapshot(
		"{\"schemaVersion\":1,\"targetType\":\"answer\",\"questionId\":10}",
		"b".repeat(64)
	);

	@BeforeEach
	void setUpSnapshotFactory() {
		when(snapshotFactory.create(anyLong(), anyList(), any(Message.class), anyList())).thenReturn(contextSnapshot);
		when(snapshotFactory.createAnswer(any(Answer.class), anyList())).thenReturn(answerSnapshot);
	}

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
		assertThat(saved.getContextSnapshot()).isEqualTo(contextSnapshot.json());
		assertThat(saved.getContextHash()).isEqualTo(contextSnapshot.hash());
		verify(snapshotFactory).create(100L, List.of(before), reportedMessage, List.of(after));
	}

	@Test
	void createMessageReportDoesNotScheduleExternalAfterCommitWork() {
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

			assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
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

	@Test
	void createHumanAnswerReportStoresAuthorImagesAndCancelledAiWork() throws Exception {
		User reporter = user(42L, "reporter@example.com", "reporter");
		User author = user(77L, "author@example.com", "author");
		Answer answer = answer(500L, Answer.createHuman(10L, 77L, "human answer"));
		Question question = question(10L, 42L);
		AnswerImage image = AnswerImage.link(500L, java.util.UUID.randomUUID(), 0);
		when(answerRepository.findById(500L)).thenReturn(Optional.of(answer));
		when(questionRepository.findActiveByIdForShare(10L)).thenReturn(Optional.of(question));
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(reporter));
		when(userRepository.getReferenceById(77L)).thenReturn(author);
		when(answerImageRepository.findByAnswerIdInOrderBySortOrderAsc(List.of(500L))).thenReturn(List.of(image));
		stubSavedReportId(900L);

		var response = service.createAnswer(principal(42L), 500L, ReportReason.abuse, "detail");

		assertThat(response.reportId()).isEqualTo(900L);
		ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
		verify(reportRepository).save(captor.capture());
		Report saved = captor.getValue();
		assertThat(saved.getTargetType()).isEqualTo(ReportTargetType.answer);
		assertThat(saved.getAnswer()).isSameAs(answer);
		assertThat(saved.getReportedUser()).isSameAs(author);
		assertThat(saved.getAiReviewState()).isEqualTo(ReportAiReviewState.cancelled);
		assertThat(saved.getContextSnapshot()).isEqualTo(answerSnapshot.json());
		verify(snapshotFactory).createAnswer(answer, List.of(image));
	}

	@Test
	void createAiAnswerReportStoresNullReportedUserAndCancelledAiWork() throws Exception {
		User reporter = user(42L, "reporter@example.com", "reporter");
		Answer answer = answer(501L, Answer.createAi(10L, "AI answer"));
		when(answerRepository.findById(501L)).thenReturn(Optional.of(answer));
		when(questionRepository.findActiveByIdForShare(10L)).thenReturn(Optional.of(question(10L, 42L)));
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(reporter));
		when(answerImageRepository.findByAnswerIdInOrderBySortOrderAsc(List.of(501L))).thenReturn(List.of());
		stubSavedReportId(901L);

		var response = service.createAnswer(principal(42L), 501L, ReportReason.etc, null);

		assertThat(response.reportId()).isEqualTo(901L);
		ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
		verify(reportRepository).save(captor.capture());
		assertThat(captor.getValue().getReportedUser()).isNull();
		assertThat(captor.getValue().getAiReviewState()).isEqualTo(ReportAiReviewState.cancelled);
	}

	@Test
	void createHumanAnswerReportKeepsSoftDeletedAuthorAttribution() throws Exception {
		User reporter = user(42L, "reporter@example.com", "reporter");
		User deletedAuthor = user(77L, "deleted@example.com", "deleted");
		deletedAuthor.markDeleted(OffsetDateTime.parse("2026-07-13T10:00:00+09:00"));
		Answer answer = answer(500L, Answer.createHuman(10L, 77L, "human answer"));
		when(answerRepository.findById(500L)).thenReturn(Optional.of(answer));
		when(questionRepository.findActiveByIdForShare(10L)).thenReturn(Optional.of(question(10L, 42L)));
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(reporter));
		when(userRepository.getReferenceById(77L)).thenReturn(deletedAuthor);
		when(answerImageRepository.findByAnswerIdInOrderBySortOrderAsc(List.of(500L))).thenReturn(List.of());
		stubSavedReportId(902L);

		service.createAnswer(principal(42L), 500L, ReportReason.harassment, null);

		ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
		verify(reportRepository).save(captor.capture());
		assertThat(captor.getValue().getReportedUser()).isSameAs(deletedAuthor);
		assertThat(captor.getValue().getReportedUser().getDeletedAt()).isNotNull();
	}

	@Test
	void createAnswerReportMapsMissingAnswerOrDeletedQuestionToAnswerNotFound() {
		when(answerRepository.findById(500L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createAnswer(principal(42L), 500L, ReportReason.spam, null))
			.isInstanceOf(AnswerNotFoundException.class);

		Answer answer = answer(501L, Answer.createHuman(10L, 77L, "answer"));
		when(answerRepository.findById(501L)).thenReturn(Optional.of(answer));
		when(questionRepository.findActiveByIdForShare(10L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createAnswer(principal(42L), 501L, ReportReason.spam, null))
			.isInstanceOf(AnswerNotFoundException.class);
		verifyNoInteractions(reportRepository);
	}

	@Test
	void createAnswerReportAllowsSelfAndDuplicateReports() throws Exception {
		User self = user(42L, "self@example.com", "self");
		Answer answer = answer(500L, Answer.createHuman(10L, 42L, "self answer"));
		when(answerRepository.findById(500L)).thenReturn(Optional.of(answer));
		when(questionRepository.findActiveByIdForShare(10L)).thenReturn(Optional.of(question(10L, 42L)));
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(self));
		when(userRepository.getReferenceById(42L)).thenReturn(self);
		when(answerImageRepository.findByAnswerIdInOrderBySortOrderAsc(List.of(500L))).thenReturn(List.of());
		stubSavedReportId(900L);

		service.createAnswer(principal(42L), 500L, ReportReason.etc, null);
		service.createAnswer(principal(42L), 500L, ReportReason.etc, null);

		verify(reportRepository, org.mockito.Mockito.times(2)).save(any(Report.class));
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

	private Answer answer(Long id, Answer answer) {
		setField(answer, "id", id);
		return answer;
	}

	private Question question(Long id, Long authorId) {
		Question question = Question.create(1L, authorId, "title", "content");
		setField(question, "id", id);
		return question;
	}

	private void stubSavedReportId(Long id) {
		when(reportRepository.save(any(Report.class))).thenAnswer(invocation -> {
			Report report = invocation.getArgument(0);
			setField(report, "id", id);
			return report;
		});
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
