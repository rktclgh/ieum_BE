package shinhan.fibri.ieum.main.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.meeting.domain.Meeting;
import shinhan.fibri.ieum.main.meeting.domain.MeetingSchedule;
import shinhan.fibri.ieum.main.meeting.domain.MeetingType;
import shinhan.fibri.ieum.main.meeting.domain.MeetingParticipant;
import shinhan.fibri.ieum.main.meeting.exception.SchedulePermissionDeniedException;
import shinhan.fibri.ieum.main.meeting.repository.MeetingParticipantRepository;
import shinhan.fibri.ieum.main.meeting.repository.MeetingRepository;
import shinhan.fibri.ieum.main.meeting.repository.MeetingScheduleRepository;
import shinhan.fibri.ieum.main.report.domain.Report;
import shinhan.fibri.ieum.main.report.domain.ReportAiReviewState;
import shinhan.fibri.ieum.main.report.domain.ReportContextSnapshot;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.main.report.domain.ReportTargetType;
import shinhan.fibri.ieum.main.report.repository.ReportRepository;

class MeetingScheduleReportServiceTest {

	private final MeetingRepository meetingRepository = org.mockito.Mockito.mock(MeetingRepository.class);
	private final MeetingScheduleRepository scheduleRepository = org.mockito.Mockito.mock(MeetingScheduleRepository.class);
	private final MeetingParticipantRepository participantRepository = org.mockito.Mockito.mock(MeetingParticipantRepository.class);
	private final UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);
	private final ReportRepository reportRepository = org.mockito.Mockito.mock(ReportRepository.class);
	private final ReportContextSnapshotFactory snapshotFactory = org.mockito.Mockito.mock(ReportContextSnapshotFactory.class);
	private final MeetingScheduleReportService service = new MeetingScheduleReportService(
		meetingRepository,
		scheduleRepository,
		participantRepository,
		userRepository,
		reportRepository,
		snapshotFactory
	);

	@Test
	void joinedMemberReportsAnotherMembersFutureScheduleAsManualReview() {
		Meeting meeting = meeting(3L, 1L);
		MeetingSchedule schedule = schedule(31L, 77L);
		User reporter = user(42L, "reporter");
		User owner = user(77L, "owner");
		ReportContextSnapshot snapshot = new ReportContextSnapshot(
			"{\"schemaVersion\":1,\"targetType\":\"schedule\",\"reported\":{\"scheduleId\":31}}",
			"c".repeat(64)
		);
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L))
			.thenReturn(Optional.of(MeetingParticipant.join(3L, 42L, OffsetDateTime.now())));
		when(scheduleRepository.findByIdAndMeetingIdAndDeletedAtIsNull(31L, 3L)).thenReturn(Optional.of(schedule));
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(reporter));
		when(userRepository.getReferenceById(77L)).thenReturn(owner);
		when(snapshotFactory.createSchedule(schedule)).thenReturn(snapshot);
		when(reportRepository.save(any(Report.class))).thenAnswer(invocation -> {
			Report report = invocation.getArgument(0);
			setField(report, "id", 91L);
			return report;
		});

		var response = service.create(principal(42L), 3L, 31L, ReportReason.spam, "광고성 일정입니다");

		assertThat(response.reportId()).isEqualTo(91L);
		ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
		verify(reportRepository).save(captor.capture());
		Report report = captor.getValue();
		assertThat(report.getTargetType()).isEqualTo(ReportTargetType.schedule);
		assertThat(report.getSchedule()).isSameAs(schedule);
		assertThat(report.getReportedUser()).isSameAs(owner);
		assertThat(report.getAiReviewState()).isEqualTo(ReportAiReviewState.cancelled);
		assertThat(report.getContextSnapshot()).isEqualTo(snapshot.json());
	}

	@Test
	void rejectsReportingOwnSchedule() {
		Meeting meeting = meeting(3L, 1L);
		MeetingSchedule schedule = schedule(31L, 42L);
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L))
			.thenReturn(Optional.of(MeetingParticipant.join(3L, 42L, OffsetDateTime.now())));
		when(scheduleRepository.findByIdAndMeetingIdAndDeletedAtIsNull(31L, 3L)).thenReturn(Optional.of(schedule));

		assertThatThrownBy(() -> service.create(principal(42L), 3L, 31L, ReportReason.spam, null))
			.isInstanceOf(SchedulePermissionDeniedException.class);
	}

	private Meeting meeting(Long id, Long hostId) {
		Meeting meeting = Meeting.create(11L, hostId, MeetingType.one_time, "저녁 모임", null, null, 7, null, null);
		setField(meeting, "id", id);
		return meeting;
	}

	private MeetingSchedule schedule(Long id, Long createdBy) {
		MeetingSchedule schedule = MeetingSchedule.createManaged(
			3L,
			createdBy,
			"용산 와인바에서 봅시다",
			"용산역 1번 출구",
			LocalDate.parse("2099-07-20"),
			LocalTime.parse("19:00"),
			null,
			1
		);
		setField(schedule, "id", id);
		return schedule;
	}

	private AuthenticatedUser principal(Long id) {
		return new AuthenticatedUser(id, "user@example.com", UserRole.user, UserStatus.active);
	}

	private User user(Long id, String nickname) {
		User user = User.createEmailUser(
			nickname + "@example.com",
			"hash",
			nickname,
			LocalDate.of(1995, 1, 1),
			GenderType.female,
			"KR"
		);
		setField(user, "id", id);
		return user;
	}

	private static void setField(Object target, String name, Object value) {
		try {
			Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
