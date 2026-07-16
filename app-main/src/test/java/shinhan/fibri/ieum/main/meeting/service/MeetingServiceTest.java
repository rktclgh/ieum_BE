package shinhan.fibri.ieum.main.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.file.domain.File;
import shinhan.fibri.ieum.common.file.repository.FileRepository;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.chat.service.ChatRoomLifecycle;
import shinhan.fibri.ieum.main.meeting.domain.Meeting;
import shinhan.fibri.ieum.main.meeting.domain.MeetingParticipant;
import shinhan.fibri.ieum.main.meeting.domain.MeetingRecurrenceRule;
import shinhan.fibri.ieum.main.meeting.domain.MeetingSchedule;
import shinhan.fibri.ieum.main.meeting.domain.MeetingType;
import shinhan.fibri.ieum.main.meeting.domain.ParticipantStatus;
import shinhan.fibri.ieum.main.meeting.domain.RecurrenceFrequency;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingRecurrenceRuleRequest;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingScheduleRequest;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingScheduleResponse;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingRequest;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingResponse;
import shinhan.fibri.ieum.main.meeting.dto.JoinMeetingResponse;
import shinhan.fibri.ieum.main.meeting.dto.KickMeetingRequest;
import shinhan.fibri.ieum.main.meeting.dto.MeetingCalendarResponse;
import shinhan.fibri.ieum.main.meeting.dto.MeetingDetailResponse;
import shinhan.fibri.ieum.main.meeting.dto.MeetingParticipantsResponse;
import shinhan.fibri.ieum.main.meeting.dto.ManageMeetingScheduleRequest;
import shinhan.fibri.ieum.main.meeting.dto.MeetingScheduleItem;
import shinhan.fibri.ieum.main.meeting.dto.MeetingSchedulesResponse;
import shinhan.fibri.ieum.main.meeting.exception.HostCannotLeaveException;
import shinhan.fibri.ieum.main.meeting.exception.KickedMemberException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingFullException;
import shinhan.fibri.ieum.main.meeting.exception.InvalidMeetingRequestException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingNotFoundException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingNotOpenException;
import shinhan.fibri.ieum.main.meeting.exception.NotMeetingMemberException;
import shinhan.fibri.ieum.main.meeting.exception.NotHostException;
import shinhan.fibri.ieum.main.meeting.exception.ParticipantNotFoundException;
import shinhan.fibri.ieum.main.meeting.exception.ScheduleAlreadyExistsException;
import shinhan.fibri.ieum.main.meeting.exception.ScheduleNotCancellableException;
import shinhan.fibri.ieum.main.meeting.exception.ScheduleNotFoundException;
import shinhan.fibri.ieum.main.meeting.exception.SchedulePermissionDeniedException;
import shinhan.fibri.ieum.main.meeting.repository.MeetingDetailProjection;
import shinhan.fibri.ieum.main.meeting.repository.MeetingCalendarProjection;
import shinhan.fibri.ieum.main.meeting.repository.MeetingParticipantProjection;
import shinhan.fibri.ieum.main.meeting.repository.MeetingParticipantRepository;
import shinhan.fibri.ieum.main.meeting.repository.MeetingRecurrenceRuleRepository;
import shinhan.fibri.ieum.main.meeting.repository.MeetingRepository;
import shinhan.fibri.ieum.main.meeting.repository.MeetingScheduleRepository;
import shinhan.fibri.ieum.main.pin.domain.PinType;
import shinhan.fibri.ieum.main.pin.dto.LocationSnapshot;
import shinhan.fibri.ieum.main.pin.repository.PinWriter;
import shinhan.fibri.ieum.main.notification.presence.MeetingCreatedEvent;

class MeetingServiceTest {

	private final MeetingRepository meetingRepository = mock(MeetingRepository.class);
	private final MeetingScheduleRepository meetingScheduleRepository = mock(MeetingScheduleRepository.class);
	private final MeetingRecurrenceRuleRepository recurrenceRuleRepository = mock(MeetingRecurrenceRuleRepository.class);
	private final MeetingParticipantRepository participantRepository = mock(MeetingParticipantRepository.class);
	private final FileRepository fileRepository = mock(FileRepository.class);
	private final PinWriter pinWriter = mock(PinWriter.class);
	private final ChatRoomLifecycle chatRoomLifecycle = mock(ChatRoomLifecycle.class);
	private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
	private final MeetingService service = new MeetingService(
		meetingRepository,
		meetingScheduleRepository,
		recurrenceRuleRepository,
		participantRepository,
		fileRepository,
		pinWriter,
		chatRoomLifecycle,
		eventPublisher
	);

	@Test
	void createCreatesPinMeetingHostParticipantAndGroupRoomInOrder() {
		UUID imageFileId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		when(fileRepository.findByFileIdAndUploaderId(imageFileId, 42L))
			.thenReturn(Optional.of(uploadedFile(imageFileId, 42L, "image/jpeg")));
		when(pinWriter.create(eq(42L), eq(PinType.meeting), any(LocationSnapshot.class))).thenReturn(11L);
		when(meetingRepository.save(any(Meeting.class))).thenAnswer(invocation -> {
			Meeting meeting = invocation.getArgument(0);
			setField(meeting, "id", 3L);
			return meeting;
		});
		when(meetingScheduleRepository.save(any(MeetingSchedule.class))).thenAnswer(invocation -> {
			MeetingSchedule schedule = invocation.getArgument(0);
			setField(schedule, "id", 31L);
			return schedule;
		});
		when(chatRoomLifecycle.createGroupRoom(3L, 42L)).thenReturn(9L);

		CreateMeetingResponse response = service.create(principal(42L), request(imageFileId));

		assertThat(response.meetingId()).isEqualTo(3L);
		assertThat(response.pinId()).isEqualTo(11L);
		assertThat(response.roomId()).isEqualTo(9L);
		assertThat(response.firstScheduleId()).isEqualTo(31L);
		verify(eventPublisher).publishEvent(new MeetingCreatedEvent(3L, 42L, "저녁 모임", 37.5, 127.0));
		InOrder order = inOrder(pinWriter, meetingRepository, meetingScheduleRepository, participantRepository, chatRoomLifecycle);
		order.verify(pinWriter).create(
			42L,
			PinType.meeting,
			new LocationSnapshot(37.5, 127.0, "서울특별시 강남구 테헤란로 123", "2번 출구 앞", "동선역 2번 출구")
		);
		order.verify(meetingRepository).save(any(Meeting.class));
		order.verify(meetingScheduleRepository).save(any(MeetingSchedule.class));
		order.verify(participantRepository).save(any(MeetingParticipant.class));
		order.verify(chatRoomLifecycle).createGroupRoom(3L, 42L);
		ArgumentCaptor<MeetingSchedule> scheduleCaptor = ArgumentCaptor.forClass(MeetingSchedule.class);
		verify(meetingScheduleRepository).save(scheduleCaptor.capture());
		assertThat(scheduleCaptor.getValue().getMeetingId()).isEqualTo(3L);
		assertThat(scheduleCaptor.getValue().getStartsAt()).isEqualTo(OffsetDateTime.parse("2026-07-10T19:00:00+09:00"));
		assertThat(scheduleCaptor.getValue().getVisibleUntil()).isEqualTo(OffsetDateTime.parse("2026-07-10T23:59:59.999999999+09:00"));
	}

	@Test
	void createOneTimeWithoutScheduleKeepsHostAndRoomAndReturnsNullScheduleId() {
		when(pinWriter.create(eq(42L), eq(PinType.meeting), any(LocationSnapshot.class))).thenReturn(11L);
		when(meetingRepository.save(any(Meeting.class))).thenAnswer(invocation -> {
			Meeting meeting = invocation.getArgument(0);
			setField(meeting, "id", 3L);
			return meeting;
		});
		when(chatRoomLifecycle.createGroupRoom(3L, 42L)).thenReturn(9L);

		CreateMeetingResponse response = service.create(
			principal(42L),
			requestWithoutSchedule(MeetingType.one_time, null)
		);

		assertThat(response.meetingId()).isEqualTo(3L);
		assertThat(response.roomId()).isEqualTo(9L);
		assertThat(response.firstScheduleId()).isNull();
		ArgumentCaptor<Meeting> meetingCaptor = ArgumentCaptor.forClass(Meeting.class);
		verify(meetingRepository).save(meetingCaptor.capture());
		assertThat(meetingCaptor.getValue().getMeetingAt()).isNull();
		verify(meetingScheduleRepository, never()).save(any(MeetingSchedule.class));
		verify(participantRepository).save(any(MeetingParticipant.class));
		verify(chatRoomLifecycle).createGroupRoom(3L, 42L);
		verify(eventPublisher).publishEvent(new MeetingCreatedEvent(3L, 42L, "저녁 모임", 37.5, 127.0));
	}

	@Test
	void createRejectsImageNotOwnedByRequester() {
		UUID imageFileId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		when(fileRepository.findByFileIdAndUploaderId(imageFileId, 42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.create(principal(42L), request(imageFileId)))
			.isInstanceOf(InvalidMeetingRequestException.class)
			.hasMessage("Invalid image");
		verify(pinWriter, never()).create(any(), any(), any(LocationSnapshot.class));
	}

	@Test
	void createRejectsNonImageFile() {
		UUID imageFileId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		when(fileRepository.findByFileIdAndUploaderId(imageFileId, 42L))
			.thenReturn(Optional.of(uploadedFile(imageFileId, 42L, "text/plain")));

		assertThatThrownBy(() -> service.create(principal(42L), request(imageFileId)))
			.isInstanceOf(InvalidMeetingRequestException.class)
			.hasMessage("Invalid image");
	}

	@Test
	void createRejectsRecurrenceRuleForOneTimeMeeting() {
		CreateMeetingRecurrenceRuleRequest recurrenceRule = weeklyRule(LocalDate.parse("2026-07-07"));

		assertThatThrownBy(() -> service.create(
			principal(42L),
			request(null, MeetingType.one_time, OffsetDateTime.parse("2026-07-07T19:00:00+09:00"), recurrenceRule)
		))
			.isInstanceOf(InvalidMeetingRequestException.class)
			.hasMessage("recurrenceRule is only allowed for recurring meeting");
		verify(pinWriter, never()).create(any(), any(), any(LocationSnapshot.class));
	}

	@Test
	void createRejectsMissingRecurrenceRuleForRecurringMeeting() {
		assertThatThrownBy(() -> service.create(
			principal(42L),
			request(null, MeetingType.recurring, OffsetDateTime.parse("2026-07-07T19:00:00+09:00"), null)
		))
			.isInstanceOf(InvalidMeetingRequestException.class)
			.hasMessage("recurrenceRule is required for recurring meeting");
		verify(pinWriter, never()).create(any(), any(), any(LocationSnapshot.class));
	}

	@Test
	void createRejectsMissingScheduleForRecurringMeetingBeforeWritingRows() {
		assertThatThrownBy(() -> service.create(
			principal(42L),
			requestWithoutSchedule(MeetingType.recurring, weeklyRule(LocalDate.parse("2026-07-07")))
		))
			.isInstanceOf(InvalidMeetingRequestException.class)
			.hasMessage("schedule is required for recurring meeting");
		verify(pinWriter, never()).create(any(), any(), any(LocationSnapshot.class));
		verify(meetingRepository, never()).save(any(Meeting.class));
	}

	@Test
	void createRejectsScheduleEndingAtOrBeforeStartBeforeWritingRows() {
		OffsetDateTime startsAt = OffsetDateTime.parse("2099-07-10T19:00:00+09:00");
		CreateMeetingRequest invalidRequest = requestWithSchedule(
			new CreateMeetingScheduleRequest(startsAt, startsAt)
		);

		assertThatThrownBy(() -> service.create(principal(42L), invalidRequest))
			.isInstanceOfSatisfying(InvalidMeetingRequestException.class, exception -> {
				assertThat(exception.code()).isEqualTo("VALIDATION_FAILED");
				assertThat(exception.field()).isEqualTo("schedule.endsAt");
				assertThat(exception).hasMessage("endsAt must be after startsAt");
			});
		verify(pinWriter, never()).create(any(), any(), any(LocationSnapshot.class));
		verify(meetingRepository, never()).save(any(Meeting.class));
	}

	@Test
	void createRecurringMeetingStoresRuleAndInitialSchedules() {
		when(pinWriter.create(eq(42L), eq(PinType.meeting), any(LocationSnapshot.class))).thenReturn(11L);
		when(meetingRepository.save(any(Meeting.class))).thenAnswer(invocation -> {
			Meeting meeting = invocation.getArgument(0);
			setField(meeting, "id", 3L);
			return meeting;
		});
		when(meetingScheduleRepository.save(any(MeetingSchedule.class))).thenAnswer(invocation -> {
			MeetingSchedule schedule = invocation.getArgument(0);
			setField(schedule, "id", 30L + schedule.getSequenceNo());
			return schedule;
		});
		when(recurrenceRuleRepository.save(any(MeetingRecurrenceRule.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(chatRoomLifecycle.createGroupRoom(3L, 42L)).thenReturn(9L);
		CreateMeetingRecurrenceRuleRequest recurrenceRule = weeklyRule(LocalDate.parse("2026-07-07"));

		CreateMeetingResponse response = service.create(
			principal(42L),
			request(null, MeetingType.recurring, OffsetDateTime.parse("2026-07-07T19:00:00+09:00"), recurrenceRule)
		);

		assertThat(response.firstScheduleId()).isEqualTo(31L);
		ArgumentCaptor<MeetingSchedule> scheduleCaptor = ArgumentCaptor.forClass(MeetingSchedule.class);
		verify(meetingScheduleRepository, org.mockito.Mockito.times(3)).save(scheduleCaptor.capture());
		assertThat(scheduleCaptor.getAllValues())
			.extracting(MeetingSchedule::getStartsAt)
			.containsExactly(
				OffsetDateTime.parse("2026-07-07T19:00:00+09:00"),
				OffsetDateTime.parse("2026-07-14T19:00:00+09:00"),
				OffsetDateTime.parse("2026-07-21T19:00:00+09:00")
			);
		assertThat(scheduleCaptor.getAllValues())
			.extracting(MeetingSchedule::getSequenceNo)
			.containsExactly(1, 2, 3);
		ArgumentCaptor<MeetingRecurrenceRule> ruleCaptor = ArgumentCaptor.forClass(MeetingRecurrenceRule.class);
		verify(recurrenceRuleRepository).save(ruleCaptor.capture());
		assertThat(ruleCaptor.getValue().getMeetingId()).isEqualTo(3L);
		assertThat(ruleCaptor.getValue().getFrequency()).isEqualTo(RecurrenceFrequency.weekly);
	}

	@Test
	void createRecurringMonthlyMeetingAnchorsIntervalAtFirstActualOccurrence() {
		when(pinWriter.create(eq(42L), eq(PinType.meeting), any(LocationSnapshot.class))).thenReturn(11L);
		when(meetingRepository.save(any(Meeting.class))).thenAnswer(invocation -> {
			Meeting meeting = invocation.getArgument(0);
			setField(meeting, "id", 3L);
			return meeting;
		});
		when(meetingScheduleRepository.save(any(MeetingSchedule.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(recurrenceRuleRepository.save(any(MeetingRecurrenceRule.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(chatRoomLifecycle.createGroupRoom(3L, 42L)).thenReturn(9L);
		CreateMeetingRecurrenceRuleRequest recurrenceRule = new CreateMeetingRecurrenceRuleRequest(
			RecurrenceFrequency.monthly,
			2,
			null,
			15,
			LocalDate.parse("2026-01-20"),
			LocalDate.parse("2026-08-31"),
			null,
			"Asia/Seoul"
		);

		service.create(
			principal(42L),
			request(null, MeetingType.recurring, OffsetDateTime.parse("2026-02-15T19:00:00+09:00"), recurrenceRule)
		);

		ArgumentCaptor<MeetingSchedule> scheduleCaptor = ArgumentCaptor.forClass(MeetingSchedule.class);
		verify(meetingScheduleRepository, org.mockito.Mockito.times(4)).save(scheduleCaptor.capture());
		assertThat(scheduleCaptor.getAllValues())
			.extracting(MeetingSchedule::getStartsAt)
			.containsExactly(
				OffsetDateTime.parse("2026-02-15T19:00:00+09:00"),
				OffsetDateTime.parse("2026-04-15T19:00:00+09:00"),
				OffsetDateTime.parse("2026-06-15T19:00:00+09:00"),
				OffsetDateTime.parse("2026-08-15T19:00:00+09:00")
			);
	}

	@Test
	void createRecurringWeeklyMeetingUsesCalendarWeekBoundariesForInterval() {
		when(pinWriter.create(eq(42L), eq(PinType.meeting), any(LocationSnapshot.class))).thenReturn(11L);
		when(meetingRepository.save(any(Meeting.class))).thenAnswer(invocation -> {
			Meeting meeting = invocation.getArgument(0);
			setField(meeting, "id", 3L);
			return meeting;
		});
		when(meetingScheduleRepository.save(any(MeetingSchedule.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(recurrenceRuleRepository.save(any(MeetingRecurrenceRule.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(chatRoomLifecycle.createGroupRoom(3L, 42L)).thenReturn(9L);
		CreateMeetingRecurrenceRuleRequest recurrenceRule = new CreateMeetingRecurrenceRuleRequest(
			RecurrenceFrequency.weekly,
			2,
			List.of(1, 2),
			null,
			LocalDate.parse("2026-07-07"),
			LocalDate.parse("2026-08-31"),
			4,
			"Asia/Seoul"
		);

		service.create(
			principal(42L),
			request(null, MeetingType.recurring, OffsetDateTime.parse("2026-07-07T19:00:00+09:00"), recurrenceRule)
		);

		ArgumentCaptor<MeetingSchedule> scheduleCaptor = ArgumentCaptor.forClass(MeetingSchedule.class);
		verify(meetingScheduleRepository, org.mockito.Mockito.times(4)).save(scheduleCaptor.capture());
		assertThat(scheduleCaptor.getAllValues())
			.extracting(MeetingSchedule::getStartsAt)
			.containsExactly(
				OffsetDateTime.parse("2026-07-07T19:00:00+09:00"),
				OffsetDateTime.parse("2026-07-20T19:00:00+09:00"),
				OffsetDateTime.parse("2026-07-21T19:00:00+09:00"),
				OffsetDateTime.parse("2026-08-03T19:00:00+09:00")
			);
	}

	@Test
	void createRecurringMeetingCachesFirstGeneratedScheduleStart() {
		when(pinWriter.create(eq(42L), eq(PinType.meeting), any(LocationSnapshot.class))).thenReturn(11L);
		when(meetingRepository.save(any(Meeting.class))).thenAnswer(invocation -> {
			Meeting meeting = invocation.getArgument(0);
			setField(meeting, "id", 3L);
			return meeting;
		});
		when(meetingScheduleRepository.save(any(MeetingSchedule.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(recurrenceRuleRepository.save(any(MeetingRecurrenceRule.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(chatRoomLifecycle.createGroupRoom(3L, 42L)).thenReturn(9L);
		CreateMeetingRecurrenceRuleRequest recurrenceRule = new CreateMeetingRecurrenceRuleRequest(
			RecurrenceFrequency.monthly,
			1,
			null,
			15,
			LocalDate.parse("2026-01-20"),
			LocalDate.parse("2026-03-31"),
			null,
			"Asia/Seoul"
		);

		service.create(
			principal(42L),
			request(null, MeetingType.recurring, OffsetDateTime.parse("2026-01-20T19:00:00+09:00"), recurrenceRule)
		);

		ArgumentCaptor<Meeting> meetingCaptor = ArgumentCaptor.forClass(Meeting.class);
		verify(meetingRepository).save(meetingCaptor.capture());
		assertThat(meetingCaptor.getValue().getMeetingAt())
			.isEqualTo(OffsetDateTime.parse("2026-02-15T19:00:00+09:00"));
	}

	@Test
	void createRecurringMeetingRejectsInvalidTimezoneBeforeWritingRows() {
		CreateMeetingRecurrenceRuleRequest recurrenceRule = new CreateMeetingRecurrenceRuleRequest(
			RecurrenceFrequency.daily,
			1,
			null,
			null,
			LocalDate.parse("2026-07-07"),
			null,
			null,
			"Foo/Bar"
		);

		assertThatThrownBy(() -> service.create(
			principal(42L),
			request(null, MeetingType.recurring, OffsetDateTime.parse("2026-07-07T19:00:00+09:00"), recurrenceRule)
		))
			.isInstanceOf(InvalidMeetingRequestException.class)
			.hasMessage("Invalid recurrenceRule");
		verify(pinWriter, never()).create(any(), any(), any(LocationSnapshot.class));
		verify(meetingRepository, never()).save(any(Meeting.class));
	}

	@Test
	void createRecurringMeetingRejectsMissingWeeklyDaysBeforeWritingRows() {
		CreateMeetingRecurrenceRuleRequest recurrenceRule = new CreateMeetingRecurrenceRuleRequest(
			RecurrenceFrequency.weekly,
			1,
			List.of(),
			null,
			LocalDate.parse("2026-07-07"),
			null,
			null,
			"Asia/Seoul"
		);

		assertThatThrownBy(() -> service.create(
			principal(42L),
			request(null, MeetingType.recurring, OffsetDateTime.parse("2026-07-07T19:00:00+09:00"), recurrenceRule)
		))
			.isInstanceOf(InvalidMeetingRequestException.class)
			.hasMessage("daysOfWeek is required for weekly recurrence");
		verify(pinWriter, never()).create(any(), any(), any(LocationSnapshot.class));
		verify(meetingRepository, never()).save(any(Meeting.class));
	}

	@Test
	void getDetailAssemblesMeetingDetailForJoinedMember() {
		UUID hostProfileFileId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UUID imageFileId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		OffsetDateTime meetingAt = OffsetDateTime.parse("2026-07-10T19:00:00+09:00");
		OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-09T10:00:00+09:00");
		when(meetingRepository.findDetailById(3L))
			.thenReturn(Optional.of(detailRow(hostProfileFileId, imageFileId, meetingAt, createdAt)));
		when(participantRepository.countByIdMeetingIdAndStatus(3L, ParticipantStatus.joined)).thenReturn(7L);
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L))
			.thenReturn(Optional.of(MeetingParticipant.join(3L, 42L, createdAt)));
		MeetingSchedule nextSchedule = MeetingSchedule.create(
			3L,
			42L,
			OffsetDateTime.parse("2026-07-14T19:00:00+09:00"),
			OffsetDateTime.parse("2026-07-14T20:00:00+09:00"),
			OffsetDateTime.parse("2026-07-14T23:59:59+09:00"),
			2
		);
		setField(nextSchedule, "id", 32L);
		when(meetingScheduleRepository.findFirstActiveSchedule(eq(3L), any(OffsetDateTime.class)))
			.thenReturn(Optional.of(nextSchedule));
		when(recurrenceRuleRepository.findByMeetingId(3L))
			.thenReturn(Optional.of(MeetingRecurrenceRule.createWeekly(
				3L,
				1,
				List.of(2),
				LocalDate.parse("2026-07-07"),
				LocalDate.parse("2026-07-21"),
				3,
				"Asia/Seoul"
			)));

		MeetingDetailResponse response = service.getDetail(principal(42L), 3L);

		assertThat(response.meetingId()).isEqualTo(3L);
		assertThat(response.pinId()).isEqualTo(11L);
		assertThat(response.roomId()).isEqualTo(9L);
		assertThat(response.title()).isEqualTo("저녁 모임");
		assertThat(response.content()).isEqualTo("같이 밥 먹어요");
		assertThat(response.location().address()).isEqualTo("서울특별시 강남구 테헤란로 123");
		assertThat(response.location().label()).isEqualTo("동선역 2번 출구");
		assertThat(response.meetingAt()).isEqualTo(meetingAt);
		assertThat(response.type()).isEqualTo("recurring");
		assertThat(response.active()).isTrue();
		assertThat(response.nextSchedule().scheduleId()).isEqualTo(32L);
		assertThat(response.nextSchedule().createdByUserId()).isEqualTo(42L);
		assertThat(response.nextSchedule().canDelete()).isFalse();
		assertThat(response.nextSchedule().startsAt()).isEqualTo(OffsetDateTime.parse("2026-07-14T19:00:00+09:00"));
		assertThat(response.nextSchedule().status()).isEqualTo("scheduled");
		assertThat(response.recurrenceRule().frequency()).isEqualTo("weekly");
		assertThat(response.recurrenceRule().daysOfWeek()).containsExactly(2);
		assertThat(response.status()).isEqualTo("open");
		assertThat(response.maxMembers()).isEqualTo(7);
		assertThat(response.participantCount()).isEqualTo(7L);
		assertThat(response.host().userId()).isEqualTo(1L);
		assertThat(response.host().nickname()).isEqualTo("오이정");
		assertThat(response.host().profileImageUrl()).isEqualTo("/api/v1/files/" + hostProfileFileId);
		assertThat(response.imageUrl()).isEqualTo("/api/v1/files/%s?v=display".formatted(imageFileId));
		assertThat(response.thumbnailUrl()).isEqualTo("/api/v1/files/%s?v=thumb".formatted(imageFileId));
		assertThat(response.location().lat()).isEqualTo(37.5);
		assertThat(response.location().lng()).isEqualTo(127.0);
		assertThat(response.myStatus()).isEqualTo("joined");
		assertThat(response.createdAt()).isEqualTo(createdAt);
	}

	@Test
	void getDetailFallsBackLegacyScheduleLocationToPinAddressWhenLabelIsBlank() {
		OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-09T10:00:00+09:00");
		MeetingDetailProjection detail = spy(detailRow(null, null, null, createdAt, "one_time"));
		when(detail.getLabel()).thenReturn("  ");
		when(detail.getAddress()).thenReturn("서울특별시 용산구 한강대로");
		MeetingSchedule legacySchedule = MeetingSchedule.create(
			3L,
			42L,
			OffsetDateTime.parse("2099-07-14T19:00:00+09:00"),
			null,
			OffsetDateTime.parse("2099-07-14T23:59:59+09:00"),
			1
		);
		when(meetingRepository.findDetailById(3L)).thenReturn(Optional.of(detail));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 1L)).thenReturn(Optional.empty());
		when(participantRepository.countByIdMeetingIdAndStatus(3L, ParticipantStatus.joined)).thenReturn(1L);
		when(meetingScheduleRepository.findFirstActiveSchedule(eq(3L), any(OffsetDateTime.class)))
			.thenReturn(Optional.of(legacySchedule));
		when(recurrenceRuleRepository.findByMeetingId(3L)).thenReturn(Optional.empty());

		MeetingDetailResponse response = service.getDetail(principal(1L), 3L);

		assertThat(response.nextSchedule().locationName()).isEqualTo("서울특별시 용산구 한강대로");
	}

	@Test
	void getDetailReturnsNullTimesForUnscheduledOneTimeMeeting() {
		OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-09T10:00:00+09:00");
		when(meetingRepository.findDetailById(3L))
			.thenReturn(Optional.of(detailRow(null, null, null, createdAt, "one_time")));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L))
			.thenReturn(Optional.of(MeetingParticipant.join(3L, 42L, createdAt)));
		when(participantRepository.countByIdMeetingIdAndStatus(3L, ParticipantStatus.joined)).thenReturn(1L);
		when(meetingScheduleRepository.findFirstActiveSchedule(eq(3L), any(OffsetDateTime.class)))
			.thenReturn(Optional.empty());
		when(recurrenceRuleRepository.findByMeetingId(3L)).thenReturn(Optional.empty());

		MeetingDetailResponse response = service.getDetail(principal(42L), 3L);

		assertThat(response.meetingAt()).isNull();
		assertThat(response.nextSchedule()).isNull();
		assertThat(response.active()).isFalse();
		assertThat(response.type()).isEqualTo("one_time");
	}

	@Test
	void getDetailReturnsInactiveWhenNoActiveScheduleExists() {
		when(meetingRepository.findDetailById(3L))
			.thenReturn(Optional.of(detailRow(null, null, OffsetDateTime.parse("2026-07-10T19:00:00+09:00"), OffsetDateTime.parse("2026-07-09T10:00:00+09:00"))));
		when(participantRepository.countByIdMeetingIdAndStatus(3L, ParticipantStatus.joined)).thenReturn(1L);
		when(meetingScheduleRepository.findFirstActiveSchedule(eq(3L), any(OffsetDateTime.class))).thenReturn(Optional.empty());

		MeetingDetailResponse response = service.getDetail(principal(1L), 3L);

		assertThat(response.active()).isFalse();
		assertThat(response.nextSchedule()).isNull();
	}

	@Test
	void getDetailReturnsHostStatusForHost() {
		when(meetingRepository.findDetailById(3L))
			.thenReturn(Optional.of(detailRow(null, null, OffsetDateTime.parse("2026-07-10T19:00:00+09:00"), OffsetDateTime.parse("2026-07-09T10:00:00+09:00"))));
		when(participantRepository.countByIdMeetingIdAndStatus(3L, ParticipantStatus.joined)).thenReturn(1L);

		MeetingDetailResponse response = service.getDetail(principal(1L), 3L);

		assertThat(response.myStatus()).isEqualTo("host");
		assertThat(response.imageUrl()).isNull();
		assertThat(response.thumbnailUrl()).isNull();
	}

	@Test
	void getDetailRejectsKickedViewer() {
		when(meetingRepository.findDetailById(3L))
			.thenReturn(Optional.of(detailRow(null, null, OffsetDateTime.parse("2026-07-10T19:00:00+09:00"), OffsetDateTime.parse("2026-07-09T10:00:00+09:00"))));
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		participant.kick();
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L)).thenReturn(Optional.of(participant));

		assertThatThrownBy(() -> service.getDetail(principal(42L), 3L))
			.isInstanceOf(KickedMemberException.class);
	}

	@Test
	void getDetailThrowsWhenMeetingDoesNotExist() {
		when(meetingRepository.findDetailById(3L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getDetail(principal(42L), 3L))
			.isInstanceOf(MeetingNotFoundException.class)
			.hasMessage("Meeting not found");
	}

	@Test
	void getParticipantsReturnsJoinedParticipantsInJoinedAtOrder() {
		UUID profileFileId = UUID.fromString("33333333-3333-3333-3333-333333333333");
		OffsetDateTime hostJoinedAt = OffsetDateTime.parse("2026-07-09T10:00:00+09:00");
		OffsetDateTime memberJoinedAt = OffsetDateTime.parse("2026-07-09T11:00:00+09:00");
		Meeting meeting = Meeting.create(
			11L,
			1L,
			MeetingType.one_time,
			"저녁 모임",
			"같이 밥 먹어요",
			OffsetDateTime.parse("2026-07-10T19:00:00+09:00"),
			7,
			null,
			null
		);
		setField(meeting, "id", 3L);
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findJoinedParticipantsByMeetingId(3L))
			.thenReturn(List.of(
				participantRow(1L, "오이정", null, hostJoinedAt),
				participantRow(42L, "참여자", profileFileId, memberJoinedAt)
			));

		MeetingParticipantsResponse response = service.getParticipants(principal(42L), 3L);

		assertThat(response.items()).hasSize(2);
		assertThat(response.items().get(0).userId()).isEqualTo(1L);
		assertThat(response.items().get(0).nickname()).isEqualTo("오이정");
		assertThat(response.items().get(0).profileImageUrl()).isNull();
		assertThat(response.items().get(0).isHost()).isTrue();
		assertThat(response.items().get(0).joinedAt()).isEqualTo(hostJoinedAt);
		assertThat(response.items().get(1).userId()).isEqualTo(42L);
		assertThat(response.items().get(1).profileImageUrl()).isEqualTo("/api/v1/files/" + profileFileId);
		assertThat(response.items().get(1).isHost()).isFalse();
		assertThat(response.items().get(1).joinedAt()).isEqualTo(memberJoinedAt);
	}

	@Test
	void getParticipantsRejectsKickedViewer() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2026-07-10T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		participant.kick();
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L)).thenReturn(Optional.of(participant));

		assertThatThrownBy(() -> service.getParticipants(principal(42L), 3L))
			.isInstanceOf(KickedMemberException.class);
	}

	@Test
	void getSchedulesReturnsMeetingSchedulesForHost() {
		Meeting meeting = meeting(3L, 42L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingSchedule first = MeetingSchedule.create(
			3L,
			42L,
			OffsetDateTime.parse("2099-07-10T19:00:00+09:00"),
			OffsetDateTime.parse("2099-07-10T20:00:00+09:00"),
			OffsetDateTime.parse("2099-07-10T23:59:59+09:00"),
			1
		);
		setField(first, "id", 31L);
		MeetingSchedule second = MeetingSchedule.create(
			3L,
			42L,
			OffsetDateTime.parse("2099-07-20T19:00:00+09:00"),
			null,
			OffsetDateTime.parse("2099-07-20T23:59:59+09:00"),
			2
		);
		second.cancel();
		setField(second, "id", 32L);
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(meeting));
		when(meetingScheduleRepository.findSchedulesInRange(
			3L,
			OffsetDateTime.parse("2099-07-01T00:00:00+09:00"),
			OffsetDateTime.parse("2099-08-01T00:00:00+09:00"),
			1000
		)).thenReturn(List.of(first, second));

		MeetingSchedulesResponse response = service.getSchedules(
			principal(42L),
			3L,
			OffsetDateTime.parse("2099-07-01T00:00:00+09:00"),
			OffsetDateTime.parse("2099-08-01T00:00:00+09:00")
		);

		assertThat(response.items()).hasSize(2);
		assertThat(response.items()).extracting(item -> item.scheduleId()).containsExactly(31L, 32L);
		assertThat(response.items()).extracting(item -> item.status()).containsExactly("scheduled", "cancelled");
		assertThat(response.items()).extracting(item -> item.createdByUserId()).containsExactly(42L, 42L);
		assertThat(response.items()).extracting(item -> item.canDelete()).containsExactly(true, false);
	}

	@Test
	void getSchedulesFallsBackLegacyScheduleFieldsToMeetingPinLabel() {
		Meeting meeting = meeting(3L, 42L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingSchedule legacySchedule = MeetingSchedule.create(
			3L,
			42L,
			OffsetDateTime.parse("2099-07-10T19:00:00+09:00"),
			null,
			OffsetDateTime.parse("2099-07-10T23:59:59+09:00"),
			1
		);
		MeetingDetailProjection pinLocation = mock(MeetingDetailProjection.class);
		when(pinLocation.getLabel()).thenReturn("용산역 1번 출구");
		when(pinLocation.getAddress()).thenReturn("서울특별시 용산구 한강대로");
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(meeting));
		when(meetingRepository.findDetailById(3L)).thenReturn(Optional.of(pinLocation));
		when(meetingScheduleRepository.findSchedulesInRange(eq(3L), any(), any(), eq(1000)))
			.thenReturn(List.of(legacySchedule));

		MeetingSchedulesResponse response = service.getSchedules(principal(42L), 3L, null, null);

		assertThat(response.items().getFirst().title()).isEqualTo("저녁 모임");
		assertThat(response.items().getFirst().locationName()).isEqualTo("용산역 1번 출구");
	}

	@Test
	void getSchedulesFallsBackLegacyScheduleLocationToMeetingPinAddressWhenLabelIsBlank() {
		Meeting meeting = meeting(3L, 42L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingSchedule legacySchedule = MeetingSchedule.create(
			3L,
			42L,
			OffsetDateTime.parse("2099-07-10T19:00:00+09:00"),
			null,
			OffsetDateTime.parse("2099-07-10T23:59:59+09:00"),
			1
		);
		MeetingDetailProjection pinLocation = mock(MeetingDetailProjection.class);
		when(pinLocation.getLabel()).thenReturn("  ");
		when(pinLocation.getAddress()).thenReturn("서울특별시 용산구 한강대로");
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(meeting));
		when(meetingRepository.findDetailById(3L)).thenReturn(Optional.of(pinLocation));
		when(meetingScheduleRepository.findSchedulesInRange(eq(3L), any(), any(), eq(1000)))
			.thenReturn(List.of(legacySchedule));

		MeetingSchedulesResponse response = service.getSchedules(principal(42L), 3L, null, null);

		assertThat(response.items().getFirst().locationName()).isEqualTo("서울특별시 용산구 한강대로");
	}

	@Test
	void getSchedulesMarksAnotherMembersScheduleAsNotDeletable() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingSchedule schedule = MeetingSchedule.create(
			3L,
			77L,
			OffsetDateTime.parse("2099-07-10T19:00:00+09:00"),
			null,
			OffsetDateTime.parse("2099-07-10T23:59:59+09:00"),
			1
		);
		setField(schedule, "id", 31L);
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L))
			.thenReturn(Optional.of(MeetingParticipant.join(3L, 42L, OffsetDateTime.now())));
		when(meetingScheduleRepository.findSchedulesInRange(eq(3L), any(), any(), eq(1000)))
			.thenReturn(List.of(schedule));

		MeetingSchedulesResponse response = service.getSchedules(principal(42L), 3L, null, null);

		assertThat(response.items().getFirst().createdByUserId()).isEqualTo(77L);
		assertThat(response.items().getFirst().canDelete()).isFalse();
	}

	@Test
	void getSchedulesDoesNotAdvertiseEditForRecurringMeetingSchedules() {
		Meeting meeting = meeting(
			3L,
			42L,
			MeetingType.recurring,
			OffsetDateTime.parse("2099-07-10T19:00:00+09:00"),
			7
		);
		MeetingSchedule schedule = MeetingSchedule.create(
			3L,
			42L,
			OffsetDateTime.parse("2099-07-10T19:00:00+09:00"),
			null,
			OffsetDateTime.parse("2099-07-10T23:59:59+09:00"),
			1
		);
		setField(schedule, "id", 31L);
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(meeting));
		when(meetingScheduleRepository.findSchedulesInRange(eq(3L), any(), any(), eq(1000)))
			.thenReturn(List.of(schedule));

		MeetingSchedulesResponse response = service.getSchedules(principal(42L), 3L, null, null);

		assertThat(response.items().getFirst().canEdit()).isFalse();
		assertThat(response.items().getFirst().canDelete()).isTrue();
		assertThat(response.items().getFirst().canReport()).isFalse();
	}

	@Test
	void getSchedulesQueriesOnlyNonDeletedSchedules() {
		Meeting meeting = meeting(3L, 42L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(meeting));

		service.getSchedules(
			principal(42L),
			3L,
			OffsetDateTime.parse("2099-07-01T00:00:00+09:00"),
			OffsetDateTime.parse("2099-08-01T00:00:00+09:00")
		);

		verify(meetingScheduleRepository).findSchedulesInRange(
			3L,
			OffsetDateTime.parse("2099-07-01T00:00:00+09:00"),
			OffsetDateTime.parse("2099-08-01T00:00:00+09:00"),
			1000
		);
	}

	@Test
	void getSchedulesRejectsRangeLongerThanOneYear() {
		Meeting meeting = meeting(3L, 42L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(meeting));

		assertThatThrownBy(() -> service.getSchedules(
			principal(42L),
			3L,
			OffsetDateTime.parse("2099-01-01T00:00:00+09:00"),
			OffsetDateTime.parse("2100-01-03T00:00:00+09:00")
		))
			.isInstanceOf(InvalidMeetingRequestException.class)
			.hasMessage("Range must not exceed 366 days");
	}

	@Test
	void getCalendarRejectsRangeLongerThanOneYear() {
		assertThatThrownBy(() -> service.getCalendar(
			principal(42L),
			OffsetDateTime.parse("2099-01-01T00:00:00+09:00"),
			OffsetDateTime.parse("2100-01-03T00:00:00+09:00")
		))
			.isInstanceOf(InvalidMeetingRequestException.class)
			.hasMessage("Range must not exceed 366 days");
	}

	@Test
	void getSchedulesRejectsNonMember() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getSchedules(principal(42L), 3L, null, null))
			.isInstanceOf(NotMeetingMemberException.class);
	}

	@Test
	void getSchedulesRejectsKickedViewer() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		participant.kick();
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L)).thenReturn(Optional.of(participant));

		assertThatThrownBy(() -> service.getSchedules(principal(42L), 3L, null, null))
			.isInstanceOf(KickedMemberException.class);
	}

	@Test
	void getCalendarReturnsJoinedScheduledSchedules() {
		when(meetingScheduleRepository.findCalendarItems(
			42L,
			OffsetDateTime.parse("2099-07-01T00:00:00+09:00"),
			OffsetDateTime.parse("2099-08-01T00:00:00+09:00"),
			1000
		)).thenReturn(List.of(calendarRow()));

		MeetingCalendarResponse response = service.getCalendar(
			principal(42L),
			OffsetDateTime.parse("2099-07-01T00:00:00+09:00"),
			OffsetDateTime.parse("2099-08-01T00:00:00+09:00")
		);

		assertThat(response.items()).hasSize(1);
		assertThat(response.items().getFirst().meetingId()).isEqualTo(3L);
		assertThat(response.items().getFirst().scheduleId()).isEqualTo(31L);
		assertThat(response.items().getFirst().title()).isEqualTo("저녁 모임");
		assertThat(response.items().getFirst().createdByUserId()).isEqualTo(42L);
		assertThat(response.items().getFirst().canDelete()).isTrue();
		assertThat(response.items().getFirst().roomId()).isEqualTo(9L);
		assertThat(response.items().getFirst().isHost()).isFalse();
	}

	@Test
	void getCalendarMapsUnscheduledPlaceholderAsNonDeletable() {
		when(meetingScheduleRepository.findCalendarItems(
			42L,
			OffsetDateTime.parse("2099-07-01T00:00:00+09:00"),
			OffsetDateTime.parse("2099-08-01T00:00:00+09:00"),
			1000
		)).thenReturn(List.of(unscheduledCalendarRow()));

		MeetingCalendarResponse response = service.getCalendar(
			principal(42L),
			OffsetDateTime.parse("2099-07-01T00:00:00+09:00"),
			OffsetDateTime.parse("2099-08-01T00:00:00+09:00")
		);

		assertThat(response.items()).singleElement().satisfies(item -> {
			assertThat(item.status()).isEqualTo("unscheduled");
			assertThat(item.scheduleId()).isNull();
			assertThat(item.startsAt()).isNull();
			assertThat(item.endsAt()).isNull();
			assertThat(item.createdByUserId()).isNull();
			assertThat(item.canDelete()).isFalse();
		});
	}

	@Test
	void getParticipantsThrowsWhenMeetingDoesNotExist() {
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getParticipants(principal(42L), 3L))
			.isInstanceOf(MeetingNotFoundException.class);
	}

	@Test
	void joinAddsNewParticipantToOpenMeetingAndGroupRoom() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2026-07-01T19:00:00+09:00"), 7);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		allowActiveSchedule(3L);
		when(meetingRepository.findGroupRoomIdByMeetingId(3L)).thenReturn(Optional.of(9L));
		when(participantRepository.findByIdMeetingIdAndIdUserIdForUpdate(3L, 42L)).thenReturn(Optional.empty());
		when(participantRepository.countByIdMeetingIdAndStatus(3L, ParticipantStatus.joined)).thenReturn(1L);

		JoinMeetingResponse response = service.join(principal(42L), 3L);

		assertThat(response.roomId()).isEqualTo(9L);
		ArgumentCaptor<MeetingParticipant> participantCaptor = ArgumentCaptor.forClass(MeetingParticipant.class);
		verify(participantRepository).save(participantCaptor.capture());
		assertThat(participantCaptor.getValue().getId().meetingId()).isEqualTo(3L);
		assertThat(participantCaptor.getValue().getId().userId()).isEqualTo(42L);
		assertThat(participantCaptor.getValue().getStatus()).isEqualTo(ParticipantStatus.joined);
		verify(chatRoomLifecycle).addMember(9L, 42L);
	}

	@Test
	void joinReturnsRoomIdWithoutMutatingWhenAlreadyJoined() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		allowActiveSchedule(3L);
		when(meetingRepository.findGroupRoomIdByMeetingId(3L)).thenReturn(Optional.of(9L));
		when(participantRepository.findByIdMeetingIdAndIdUserIdForUpdate(3L, 42L)).thenReturn(Optional.of(participant));

		JoinMeetingResponse response = service.join(principal(42L), 3L);

		assertThat(response.roomId()).isEqualTo(9L);
		verify(participantRepository, never()).save(any(MeetingParticipant.class));
		verify(participantRepository, never()).countByIdMeetingIdAndStatus(any(), any());
		verify(chatRoomLifecycle, never()).addMember(any(), any());
	}

	@Test
	void joinRejoinsLeftParticipantAndRestoresGroupRoomMember() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		participant.leave();
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		allowActiveSchedule(3L);
		when(meetingRepository.findGroupRoomIdByMeetingId(3L)).thenReturn(Optional.of(9L));
		when(participantRepository.findByIdMeetingIdAndIdUserIdForUpdate(3L, 42L)).thenReturn(Optional.of(participant));
		when(participantRepository.countByIdMeetingIdAndStatus(3L, ParticipantStatus.joined)).thenReturn(1L);

		JoinMeetingResponse response = service.join(principal(42L), 3L);

		assertThat(response.roomId()).isEqualTo(9L);
		assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.joined);
		verify(chatRoomLifecycle).addMember(9L, 42L);
	}

	@Test
	void joinRejectsClosedMeeting() {
		Meeting meeting = meeting(3L, 1L, null, 7);
		meeting.close();
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));

		assertThatThrownBy(() -> service.join(principal(42L), 3L))
			.isInstanceOf(MeetingNotOpenException.class);
		verify(chatRoomLifecycle, never()).addMember(any(), any());
	}

	@Test
	void joinAddsNewParticipantToUnscheduledOpenMeetingAndGroupRoom() {
		Meeting meeting = meeting(3L, 1L, null, 7);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(meetingRepository.findGroupRoomIdByMeetingId(3L)).thenReturn(Optional.of(9L));
		when(participantRepository.findByIdMeetingIdAndIdUserIdForUpdate(3L, 42L)).thenReturn(Optional.empty());
		when(participantRepository.countByIdMeetingIdAndStatus(3L, ParticipantStatus.joined)).thenReturn(1L);

		JoinMeetingResponse response = service.join(principal(42L), 3L);

		assertThat(response.roomId()).isEqualTo(9L);
		verify(meetingScheduleRepository, never()).existsActiveSchedule(any(), any());
		verify(participantRepository).save(any(MeetingParticipant.class));
		verify(chatRoomLifecycle).addMember(9L, 42L);
	}

	@Test
	void joinRejectsFullMeeting() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 2);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		allowActiveSchedule(3L);
		when(meetingRepository.findGroupRoomIdByMeetingId(3L)).thenReturn(Optional.of(9L));
		when(participantRepository.findByIdMeetingIdAndIdUserIdForUpdate(3L, 42L)).thenReturn(Optional.empty());
		when(participantRepository.countByIdMeetingIdAndStatus(3L, ParticipantStatus.joined)).thenReturn(2L);

		assertThatThrownBy(() -> service.join(principal(42L), 3L))
			.isInstanceOf(MeetingFullException.class);
		verify(participantRepository, never()).save(any(MeetingParticipant.class));
		verify(chatRoomLifecycle, never()).addMember(any(), any());
	}

	@Test
	void joinRejectsKickedParticipant() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		participant.kick();
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserIdForUpdate(3L, 42L)).thenReturn(Optional.of(participant));

		assertThatThrownBy(() -> service.join(principal(42L), 3L))
			.isInstanceOf(KickedMemberException.class);
		verify(participantRepository).findByIdMeetingIdAndIdUserIdForUpdate(3L, 42L);
		verify(chatRoomLifecycle, never()).addMember(any(), any());
	}

	@Test
	void addManagedScheduleAllowsAnotherFutureScheduleAndRefreshesTheLegacyCache() {
		Meeting meeting = meeting(3L, 42L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 99L))
			.thenReturn(Optional.of(MeetingParticipant.join(3L, 99L, OffsetDateTime.now())));
		when(meetingScheduleRepository.findMaxSequenceNoByMeetingId(3L)).thenReturn(1);
		when(meetingScheduleRepository.save(any(MeetingSchedule.class))).thenAnswer(invocation -> {
			MeetingSchedule schedule = invocation.getArgument(0);
			setField(schedule, "id", 32L);
			return schedule;
		});
		when(meetingScheduleRepository.findNextActiveStartsAt(eq(3L), any(OffsetDateTime.class)))
			.thenReturn(Optional.of(OffsetDateTime.parse("2099-07-10T19:00:00+09:00")));

		CreateMeetingScheduleResponse response = service.addManagedSchedule(
			principal(99L),
			3L,
			new ManageMeetingScheduleRequest(
				"용산 와인바에서 봅시다",
				"용산역 1번 출구",
				OffsetDateTime.parse("2099-07-20T19:00:00+09:00"),
				OffsetDateTime.parse("2099-07-20T21:00:00+09:00")
			)
		);

		assertThat(response.scheduleId()).isEqualTo(32L);
		assertThat(meeting.getMeetingAt()).isEqualTo(OffsetDateTime.parse("2099-07-10T19:00:00+09:00"));
		ArgumentCaptor<MeetingSchedule> scheduleCaptor = ArgumentCaptor.forClass(MeetingSchedule.class);
		verify(meetingScheduleRepository).save(scheduleCaptor.capture());
		assertThat(scheduleCaptor.getValue().getTitle()).isEqualTo("용산 와인바에서 봅시다");
		assertThat(scheduleCaptor.getValue().getLocationName()).isEqualTo("용산역 1번 출구");
		assertThat(scheduleCaptor.getValue().getCreatedBy()).isEqualTo(99L);
		assertThat(scheduleCaptor.getValue().getSequenceNo()).isEqualTo(2);
		verify(meetingScheduleRepository, never()).existsActiveSchedule(eq(3L), any(OffsetDateTime.class));
	}

	@Test
	void updateManagedScheduleAllowsCreatorAndReturnsCurrentCapabilities() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingSchedule schedule = MeetingSchedule.createManaged(
			3L,
			42L,
			"기존 일정",
			"기존 장소",
			OffsetDateTime.parse("2099-07-10T19:00:00+09:00"),
			null,
			OffsetDateTime.parse("2099-07-10T23:59:59+09:00"),
			1
		);
		setField(schedule, "id", 31L);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L))
			.thenReturn(Optional.of(MeetingParticipant.join(3L, 42L, OffsetDateTime.now())));
		when(meetingScheduleRepository.findByIdAndMeetingIdAndDeletedAtIsNull(31L, 3L))
			.thenReturn(Optional.of(schedule));
		when(meetingScheduleRepository.findNextActiveStartsAt(eq(3L), any(OffsetDateTime.class)))
			.thenReturn(Optional.of(OffsetDateTime.parse("2099-07-11T19:00:00+09:00")));

		MeetingScheduleItem response = service.updateManagedSchedule(
			principal(42L),
			3L,
			31L,
			new ManageMeetingScheduleRequest(
				"수정 일정",
				"수정 장소",
				OffsetDateTime.parse("2099-07-11T19:00:00+09:00"),
				null
			)
		);

		assertThat(schedule.getTitle()).isEqualTo("수정 일정");
		assertThat(schedule.getLocationName()).isEqualTo("수정 장소");
		assertThat(meeting.getMeetingAt()).isEqualTo(OffsetDateTime.parse("2099-07-11T19:00:00+09:00"));
		assertThat(response.canEdit()).isTrue();
		assertThat(response.canDelete()).isTrue();
		assertThat(response.canReport()).isFalse();
	}

	@Test
	void addScheduleCreatesOneTimeScheduleAndUpdatesMeetingAtCache() {
		Meeting meeting = meeting(3L, 42L, OffsetDateTime.parse("2026-07-01T19:00:00+09:00"), 7);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L))
			.thenReturn(Optional.of(MeetingParticipant.join(3L, 42L, OffsetDateTime.now())));
		when(meetingScheduleRepository.existsActiveSchedule(eq(3L), any(OffsetDateTime.class))).thenReturn(false);
		when(meetingScheduleRepository.findMaxSequenceNoByMeetingId(3L)).thenReturn(1);
		when(meetingScheduleRepository.save(any(MeetingSchedule.class))).thenAnswer(invocation -> {
			MeetingSchedule schedule = invocation.getArgument(0);
			setField(schedule, "id", 32L);
			return schedule;
		});

		CreateMeetingScheduleResponse response = service.addSchedule(
			principal(42L),
			3L,
			new CreateMeetingScheduleRequest(
				OffsetDateTime.parse("2099-07-10T19:00:00+09:00"),
				OffsetDateTime.parse("2099-07-10T20:00:00+09:00")
			)
		);

		assertThat(response.scheduleId()).isEqualTo(32L);
		assertThat(meeting.getMeetingAt()).isEqualTo(OffsetDateTime.parse("2099-07-10T19:00:00+09:00"));
		ArgumentCaptor<MeetingSchedule> scheduleCaptor = ArgumentCaptor.forClass(MeetingSchedule.class);
		verify(meetingScheduleRepository).save(scheduleCaptor.capture());
		assertThat(scheduleCaptor.getValue().getSequenceNo()).isEqualTo(2);
		assertThat(scheduleCaptor.getValue().getCreatedBy()).isEqualTo(42L);
		assertThat(scheduleCaptor.getValue().getVisibleUntil()).isEqualTo(OffsetDateTime.parse("2099-07-10T23:59:59.999999999+09:00"));
	}

	@Test
	void addScheduleAllowsJoinedParticipantAndStoresCreator() {
		Meeting meeting = meeting(3L, 42L, OffsetDateTime.parse("2026-07-01T19:00:00+09:00"), 7);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 99L))
			.thenReturn(Optional.of(MeetingParticipant.join(3L, 99L, OffsetDateTime.now())));
		when(meetingScheduleRepository.existsActiveSchedule(eq(3L), any(OffsetDateTime.class))).thenReturn(false);
		when(meetingScheduleRepository.findMaxSequenceNoByMeetingId(3L)).thenReturn(1);
		when(meetingScheduleRepository.save(any(MeetingSchedule.class))).thenAnswer(invocation -> {
			MeetingSchedule schedule = invocation.getArgument(0);
			setField(schedule, "id", 32L);
			return schedule;
		});

		CreateMeetingScheduleResponse response = service.addSchedule(
			principal(99L),
			3L,
			new CreateMeetingScheduleRequest(OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), null)
		);

		assertThat(response.scheduleId()).isEqualTo(32L);
		ArgumentCaptor<MeetingSchedule> scheduleCaptor = ArgumentCaptor.forClass(MeetingSchedule.class);
		verify(meetingScheduleRepository).save(scheduleCaptor.capture());
		assertThat(scheduleCaptor.getValue().getCreatedBy()).isEqualTo(99L);
	}

	@Test
	void addScheduleRejectsScheduleEndingBeforeStartBeforeReadingMeeting() {
		CreateMeetingScheduleRequest request = new CreateMeetingScheduleRequest(
			OffsetDateTime.parse("2099-07-10T19:00:00+09:00"),
			OffsetDateTime.parse("2099-07-10T18:59:59+09:00")
		);

		assertThatThrownBy(() -> service.addSchedule(principal(42L), 3L, request))
			.isInstanceOfSatisfying(InvalidMeetingRequestException.class, exception -> {
				assertThat(exception.code()).isEqualTo("VALIDATION_FAILED");
				assertThat(exception.field()).isEqualTo("endsAt");
				assertThat(exception).hasMessage("endsAt must be after startsAt");
			});
		verify(meetingRepository, never()).findActiveByIdForUpdate(any());
		verify(meetingScheduleRepository, never()).save(any(MeetingSchedule.class));
	}

	@Test
	void addScheduleRejectsNonMember() {
		Meeting meeting = meeting(3L, 42L, OffsetDateTime.parse("2026-07-01T19:00:00+09:00"), 7);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.addSchedule(
			principal(99L),
			3L,
			new CreateMeetingScheduleRequest(OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), null)
		))
			.isInstanceOf(NotMeetingMemberException.class);
		verify(meetingScheduleRepository, never()).save(any(MeetingSchedule.class));
	}

	@Test
	void addScheduleRejectsAdminWithoutJoinedMembership() {
		Meeting meeting = meeting(3L, 42L, OffsetDateTime.parse("2026-07-01T19:00:00+09:00"), 7);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.addSchedule(
			adminPrincipal(99L),
			3L,
			new CreateMeetingScheduleRequest(OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), null)
		)).isInstanceOf(NotMeetingMemberException.class);
		verify(meetingScheduleRepository, never()).save(any(MeetingSchedule.class));
	}

	@Test
	void addScheduleRejectsLeftMember() {
		Meeting meeting = meeting(3L, 42L, OffsetDateTime.parse("2026-07-01T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 99L, OffsetDateTime.now());
		participant.leave();
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 99L)).thenReturn(Optional.of(participant));

		assertThatThrownBy(() -> service.addSchedule(
			principal(99L),
			3L,
			new CreateMeetingScheduleRequest(OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), null)
		)).isInstanceOf(NotMeetingMemberException.class);
	}

	@Test
	void addScheduleRejectsKickedMember() {
		Meeting meeting = meeting(3L, 42L, OffsetDateTime.parse("2026-07-01T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 99L, OffsetDateTime.now());
		participant.kick();
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 99L)).thenReturn(Optional.of(participant));

		assertThatThrownBy(() -> service.addSchedule(
			principal(99L),
			3L,
			new CreateMeetingScheduleRequest(OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), null)
		)).isInstanceOf(KickedMemberException.class);
	}

	@Test
	void addScheduleRejectsRecurringMeeting() {
		Meeting meeting = meeting(3L, 42L, MeetingType.recurring, OffsetDateTime.parse("2026-07-01T19:00:00+09:00"), 7);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L))
			.thenReturn(Optional.of(MeetingParticipant.join(3L, 42L, OffsetDateTime.now())));

		assertThatThrownBy(() -> service.addSchedule(
			principal(42L),
			3L,
			new CreateMeetingScheduleRequest(OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), null)
		))
			.isInstanceOf(InvalidMeetingRequestException.class)
			.hasMessage("recurring schedule is managed by recurrenceRule");
		verify(meetingScheduleRepository, never()).save(any(MeetingSchedule.class));
	}

	@Test
	void addScheduleRejectsWhenActiveScheduleAlreadyExists() {
		Meeting meeting = meeting(3L, 42L, OffsetDateTime.parse("2026-07-01T19:00:00+09:00"), 7);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L))
			.thenReturn(Optional.of(MeetingParticipant.join(3L, 42L, OffsetDateTime.now())));
		when(meetingScheduleRepository.existsActiveSchedule(eq(3L), any(OffsetDateTime.class))).thenReturn(true);

		assertThatThrownBy(() -> service.addSchedule(
			principal(42L),
			3L,
			new CreateMeetingScheduleRequest(OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), null)
		))
			.isInstanceOf(ScheduleAlreadyExistsException.class);
		verify(meetingScheduleRepository, never()).save(any(MeetingSchedule.class));
	}

	@Test
	void cancelScheduleCancelsScheduleAndUpdatesMeetingAtCacheToNextSchedule() {
		Meeting meeting = meeting(3L, 42L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingSchedule schedule = MeetingSchedule.create(
			3L,
			42L,
			OffsetDateTime.parse("2099-07-10T19:00:00+09:00"),
			null,
			OffsetDateTime.parse("2099-07-10T23:59:59+09:00"),
			1
		);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(meetingScheduleRepository.findByIdAndMeetingIdAndDeletedAtIsNull(31L, 3L)).thenReturn(Optional.of(schedule));
		when(meetingScheduleRepository.findNextActiveStartsAt(eq(3L), any(OffsetDateTime.class)))
			.thenReturn(Optional.of(OffsetDateTime.parse("2099-07-20T19:00:00+09:00")));

		service.cancelSchedule(principal(42L), 3L, 31L);

		assertThat(schedule.getStatus()).isEqualTo(shinhan.fibri.ieum.main.meeting.domain.MeetingScheduleStatus.cancelled);
		assertThat(meeting.getMeetingAt()).isEqualTo(OffsetDateTime.parse("2099-07-20T19:00:00+09:00"));
	}

	@Test
	void cancelScheduleAllowsAdminOperator() {
		Meeting meeting = meeting(3L, 42L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingSchedule schedule = MeetingSchedule.create(
			3L,
			null,
			OffsetDateTime.parse("2099-07-10T19:00:00+09:00"),
			null,
			OffsetDateTime.parse("2099-07-10T23:59:59+09:00"),
			1
		);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(meetingScheduleRepository.findByIdAndMeetingIdAndDeletedAtIsNull(31L, 3L)).thenReturn(Optional.of(schedule));
		when(meetingScheduleRepository.findNextActiveStartsAt(eq(3L), any(OffsetDateTime.class))).thenReturn(Optional.empty());

		service.cancelSchedule(adminPrincipal(99L), 3L, 31L);

		assertThat(schedule.getStatus()).isEqualTo(shinhan.fibri.ieum.main.meeting.domain.MeetingScheduleStatus.cancelled);
		assertThat(meeting.getMeetingAt()).isNull();
	}

	@Test
	void cancelScheduleAllowsJoinedCreatorAndClearsLastScheduleCache() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingSchedule schedule = MeetingSchedule.create(
			3L,
			42L,
			OffsetDateTime.parse("2099-07-10T19:00:00+09:00"),
			null,
			OffsetDateTime.parse("2099-07-10T23:59:59+09:00"),
			1
		);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L))
			.thenReturn(Optional.of(MeetingParticipant.join(3L, 42L, OffsetDateTime.now())));
		when(meetingScheduleRepository.findByIdAndMeetingIdAndDeletedAtIsNull(31L, 3L)).thenReturn(Optional.of(schedule));
		when(meetingScheduleRepository.findNextActiveStartsAt(eq(3L), any(OffsetDateTime.class))).thenReturn(Optional.empty());

		service.cancelSchedule(principal(42L), 3L, 31L);

		assertThat(schedule.getStatus()).isEqualTo(shinhan.fibri.ieum.main.meeting.domain.MeetingScheduleStatus.cancelled);
		assertThat(meeting.getMeetingAt()).isNull();
	}

	@Test
	void cancelScheduleAllowsHostWithoutParticipantRow() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingSchedule schedule = MeetingSchedule.create(
			3L, 42L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), null,
			OffsetDateTime.parse("2099-07-10T23:59:59+09:00"), 1
		);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(meetingScheduleRepository.findByIdAndMeetingIdAndDeletedAtIsNull(31L, 3L)).thenReturn(Optional.of(schedule));
		when(meetingScheduleRepository.findNextActiveStartsAt(eq(3L), any(OffsetDateTime.class))).thenReturn(Optional.empty());

		service.cancelSchedule(principal(1L), 3L, 31L);

		assertThat(schedule.getStatus()).isEqualTo(shinhan.fibri.ieum.main.meeting.domain.MeetingScheduleStatus.cancelled);
	}

	@Test
	void cancelScheduleAllowsAdminEvenWhenKicked() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingSchedule schedule = MeetingSchedule.create(
			3L, 42L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), null,
			OffsetDateTime.parse("2099-07-10T23:59:59+09:00"), 1
		);
		MeetingParticipant adminParticipant = MeetingParticipant.join(3L, 99L, OffsetDateTime.now());
		adminParticipant.kick();
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 99L)).thenReturn(Optional.of(adminParticipant));
		when(meetingScheduleRepository.findByIdAndMeetingIdAndDeletedAtIsNull(31L, 3L)).thenReturn(Optional.of(schedule));
		when(meetingScheduleRepository.findNextActiveStartsAt(eq(3L), any(OffsetDateTime.class))).thenReturn(Optional.empty());

		service.cancelSchedule(adminPrincipal(99L), 3L, 31L);

		assertThat(schedule.getStatus()).isEqualTo(shinhan.fibri.ieum.main.meeting.domain.MeetingScheduleStatus.cancelled);
	}

	@Test
	void cancelScheduleAllowsAdminEvenWhenLeft() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingSchedule schedule = MeetingSchedule.create(
			3L, 42L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), null,
			OffsetDateTime.parse("2099-07-10T23:59:59+09:00"), 1
		);
		MeetingParticipant adminParticipant = MeetingParticipant.join(3L, 99L, OffsetDateTime.now());
		adminParticipant.leave();
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 99L)).thenReturn(Optional.of(adminParticipant));
		when(meetingScheduleRepository.findByIdAndMeetingIdAndDeletedAtIsNull(31L, 3L)).thenReturn(Optional.of(schedule));
		when(meetingScheduleRepository.findNextActiveStartsAt(eq(3L), any(OffsetDateTime.class))).thenReturn(Optional.empty());

		service.cancelSchedule(adminPrincipal(99L), 3L, 31L);

		assertThat(schedule.getStatus()).isEqualTo(shinhan.fibri.ieum.main.meeting.domain.MeetingScheduleStatus.cancelled);
	}

	@Test
	void cancelScheduleRejectsNonMemberBeforeScheduleLookup() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.cancelSchedule(principal(42L), 3L, 31L))
			.isInstanceOf(NotMeetingMemberException.class);
		verify(meetingScheduleRepository, never()).findByIdAndMeetingIdAndDeletedAtIsNull(any(), any());
	}

	@Test
	void cancelScheduleRejectsJoinedNonCreator() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingSchedule schedule = MeetingSchedule.create(
			3L, 77L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), null,
			OffsetDateTime.parse("2099-07-10T23:59:59+09:00"), 1
		);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L))
			.thenReturn(Optional.of(MeetingParticipant.join(3L, 42L, OffsetDateTime.now())));
		when(meetingScheduleRepository.findByIdAndMeetingIdAndDeletedAtIsNull(31L, 3L)).thenReturn(Optional.of(schedule));

		assertThatThrownBy(() -> service.cancelSchedule(principal(42L), 3L, 31L))
			.isInstanceOf(SchedulePermissionDeniedException.class);
	}

	@Test
	void cancelScheduleRejectsJoinedUserWhenCreatorWasPurged() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingSchedule schedule = MeetingSchedule.create(
			3L, null, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), null,
			OffsetDateTime.parse("2099-07-10T23:59:59+09:00"), 1
		);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L))
			.thenReturn(Optional.of(MeetingParticipant.join(3L, 42L, OffsetDateTime.now())));
		when(meetingScheduleRepository.findByIdAndMeetingIdAndDeletedAtIsNull(31L, 3L)).thenReturn(Optional.of(schedule));

		assertThatThrownBy(() -> service.cancelSchedule(principal(42L), 3L, 31L))
			.isInstanceOf(SchedulePermissionDeniedException.class);
	}

	@Test
	void cancelScheduleRejectsLeftCreator() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingSchedule schedule = MeetingSchedule.create(
			3L, 42L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), null,
			OffsetDateTime.parse("2099-07-10T23:59:59+09:00"), 1
		);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.now());
		participant.leave();
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L)).thenReturn(Optional.of(participant));
		when(meetingScheduleRepository.findByIdAndMeetingIdAndDeletedAtIsNull(31L, 3L)).thenReturn(Optional.of(schedule));

		assertThatThrownBy(() -> service.cancelSchedule(principal(42L), 3L, 31L))
			.isInstanceOf(NotMeetingMemberException.class);
	}

	@Test
	void cancelScheduleRejectsKickedCreator() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingSchedule schedule = MeetingSchedule.create(
			3L, 42L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), null,
			OffsetDateTime.parse("2099-07-10T23:59:59+09:00"), 1
		);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.now());
		participant.kick();
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L)).thenReturn(Optional.of(participant));
		when(meetingScheduleRepository.findByIdAndMeetingIdAndDeletedAtIsNull(31L, 3L)).thenReturn(Optional.of(schedule));

		assertThatThrownBy(() -> service.cancelSchedule(principal(42L), 3L, 31L))
			.isInstanceOf(KickedMemberException.class);
	}

	@Test
	void cancelScheduleRejectsMissingSchedule() {
		Meeting meeting = meeting(3L, 42L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(meetingScheduleRepository.findByIdAndMeetingIdAndDeletedAtIsNull(31L, 3L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.cancelSchedule(principal(42L), 3L, 31L))
			.isInstanceOf(ScheduleNotFoundException.class);
	}

	@Test
	void cancelScheduleRejectsAlreadyCancelledSchedule() {
		Meeting meeting = meeting(3L, 42L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingSchedule schedule = MeetingSchedule.create(
			3L,
			42L,
			OffsetDateTime.parse("2099-07-10T19:00:00+09:00"),
			null,
			OffsetDateTime.parse("2099-07-10T23:59:59+09:00"),
			1
		);
		schedule.cancel();
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(meetingScheduleRepository.findByIdAndMeetingIdAndDeletedAtIsNull(31L, 3L)).thenReturn(Optional.of(schedule));

		assertThatThrownBy(() -> service.cancelSchedule(principal(42L), 3L, 31L))
			.isInstanceOf(ScheduleNotCancellableException.class);
	}

	@Test
	void leaveMarksParticipantLeftAndRemovesGroupRoomMember() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2026-07-01T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));
		when(meetingRepository.findGroupRoomIdByMeetingId(3L)).thenReturn(Optional.of(9L));
		when(participantRepository.findByIdMeetingIdAndIdUserIdForUpdate(3L, 42L)).thenReturn(Optional.of(participant));

		service.leave(principal(42L), 3L);

		assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.left);
		verify(participantRepository).findByIdMeetingIdAndIdUserIdForUpdate(3L, 42L);
		verify(chatRoomLifecycle).removeGroupMemberWithDepartureMessage(9L, 42L);
		verify(chatRoomLifecycle, never()).removeMember(any(), any());
	}

	@Test
	void leaveRejectsHost() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));

		assertThatThrownBy(() -> service.leave(principal(1L), 3L))
			.isInstanceOf(HostCannotLeaveException.class);
		verify(chatRoomLifecycle, never()).removeGroupMemberWithDepartureMessage(any(), any());
	}

	@Test
	void leaveRejectsMissingOrInactiveParticipant() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		participant.leave();
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserIdForUpdate(3L, 42L)).thenReturn(Optional.of(participant));

		assertThatThrownBy(() -> service.leave(principal(42L), 3L))
			.isInstanceOf(ParticipantNotFoundException.class);
		verify(chatRoomLifecycle, never()).removeGroupMemberWithDepartureMessage(any(), any());
	}

	@Test
	void leaveIgnoresMissingActiveChatMember() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));
		when(meetingRepository.findGroupRoomIdByMeetingId(3L)).thenReturn(Optional.of(9L));
		when(participantRepository.findByIdMeetingIdAndIdUserIdForUpdate(3L, 42L)).thenReturn(Optional.of(participant));
		doThrow(new NotRoomMemberException()).when(chatRoomLifecycle).removeGroupMemberWithDepartureMessage(9L, 42L);

		service.leave(principal(42L), 3L);

		assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.left);
		verify(chatRoomLifecycle).removeGroupMemberWithDepartureMessage(9L, 42L);
	}

	@Test
	void kickMarksParticipantKickedAndRemovesGroupRoomMember() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));
		when(meetingRepository.findGroupRoomIdByMeetingId(3L)).thenReturn(Optional.of(9L));
		when(participantRepository.findByIdMeetingIdAndIdUserIdForUpdate(3L, 42L)).thenReturn(Optional.of(participant));

		service.kick(principal(1L), 3L, new KickMeetingRequest(42L));

		assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.kicked);
		verify(participantRepository).findByIdMeetingIdAndIdUserIdForUpdate(3L, 42L);
		verify(chatRoomLifecycle).removeGroupMemberWithDepartureMessage(9L, 42L);
		verify(chatRoomLifecycle, never()).removeMember(any(), any());
	}

	@Test
	void kickRejectsNonHost() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));

		assertThatThrownBy(() -> service.kick(principal(42L), 3L, new KickMeetingRequest(99L)))
			.isInstanceOf(NotHostException.class);
		verify(chatRoomLifecycle, never()).removeGroupMemberWithDepartureMessage(any(), any());
	}

	@Test
	void kickRejectsHostTarget() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));

		assertThatThrownBy(() -> service.kick(principal(1L), 3L, new KickMeetingRequest(1L)))
			.isInstanceOf(InvalidMeetingRequestException.class)
			.hasMessage("Host cannot be kicked");
		verify(chatRoomLifecycle, never()).removeGroupMemberWithDepartureMessage(any(), any());
	}

	@Test
	void kickRejectsMissingOrInactiveParticipant() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		participant.leave();
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserIdForUpdate(3L, 42L)).thenReturn(Optional.of(participant));

		assertThatThrownBy(() -> service.kick(principal(1L), 3L, new KickMeetingRequest(42L)))
			.isInstanceOf(ParticipantNotFoundException.class);
		verify(chatRoomLifecycle, never()).removeGroupMemberWithDepartureMessage(any(), any());
	}

	@Test
	void kickIgnoresMissingActiveChatMember() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));
		when(meetingRepository.findGroupRoomIdByMeetingId(3L)).thenReturn(Optional.of(9L));
		when(participantRepository.findByIdMeetingIdAndIdUserIdForUpdate(3L, 42L)).thenReturn(Optional.of(participant));
		doThrow(new NotRoomMemberException()).when(chatRoomLifecycle).removeGroupMemberWithDepartureMessage(9L, 42L);

		service.kick(principal(1L), 3L, new KickMeetingRequest(42L));

		assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.kicked);
		verify(chatRoomLifecycle).removeGroupMemberWithDepartureMessage(9L, 42L);
	}

	@Test
	void closeMarksOpenMeetingClosed() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));

		service.close(principal(1L), 3L);

		assertThat(meeting.getStatus()).isEqualTo(shinhan.fibri.ieum.main.meeting.domain.MeetingStatus.closed);
	}

	@Test
	void closeRejectsNonHost() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));

		assertThatThrownBy(() -> service.close(principal(42L), 3L))
			.isInstanceOf(NotHostException.class);
		assertThat(meeting.getStatus()).isEqualTo(shinhan.fibri.ieum.main.meeting.domain.MeetingStatus.open);
	}

	@Test
	void closeRejectsAlreadyClosedMeeting() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		meeting.close();
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));

		assertThatThrownBy(() -> service.close(principal(1L), 3L))
			.isInstanceOf(MeetingNotOpenException.class);
	}

	@Test
	void cancelMarksMeetingCancelledAndSoftDeletesPin() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(meeting));

		service.cancel(principal(1L), 3L);

		assertThat(meeting.getStatus()).isEqualTo(shinhan.fibri.ieum.main.meeting.domain.MeetingStatus.cancelled);
		assertThat(meeting.getDeletedAt()).isNotNull();
		verify(pinWriter).softDelete(org.mockito.ArgumentMatchers.eq(11L), any(OffsetDateTime.class));
	}

	@Test
	void cancelDisbandsTheConnectedGroupRoom() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(meeting));

		service.cancel(principal(1L), 3L);

		verify(chatRoomLifecycle).disbandGroupRoom(3L);
	}

	@Test
	void cancelRejectsNonHost() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(meeting));

		assertThatThrownBy(() -> service.cancel(principal(42L), 3L))
			.isInstanceOf(NotHostException.class);
		assertThat(meeting.getDeletedAt()).isNull();
		verify(pinWriter, never()).softDelete(any(), any());
	}

	@Test
	void cancelThrowsWhenMeetingDoesNotExist() {
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.cancel(principal(1L), 3L))
			.isInstanceOf(MeetingNotFoundException.class);
		verify(pinWriter, never()).softDelete(any(), any());
	}

	private CreateMeetingRequest request(UUID imageFileId) {
		return request(
			imageFileId,
			MeetingType.one_time,
			OffsetDateTime.parse("2026-07-10T19:00:00+09:00"),
			null
		);
	}

	private CreateMeetingRequest request(
		UUID imageFileId,
		MeetingType type,
		OffsetDateTime startsAt,
		CreateMeetingRecurrenceRuleRequest recurrenceRule
	) {
		return new CreateMeetingRequest(
			"저녁 모임",
			"같이 밥 먹어요",
			type,
			new LocationSnapshot(37.5, 127.0, "서울특별시 강남구 테헤란로 123", "2번 출구 앞", "동선역 2번 출구"),
			new CreateMeetingScheduleRequest(
				startsAt,
				null
			),
			recurrenceRule,
			7,
			imageFileId
		);
	}

	private CreateMeetingRequest requestWithoutSchedule(
		MeetingType type,
		CreateMeetingRecurrenceRuleRequest recurrenceRule
	) {
		return new CreateMeetingRequest(
			"저녁 모임",
			"같이 밥 먹어요",
			type,
			new LocationSnapshot(37.5, 127.0, "서울특별시 강남구 테헤란로 123", "2번 출구 앞", "동선역 2번 출구"),
			null,
			recurrenceRule,
			7,
			null
		);
	}

	private CreateMeetingRequest requestWithSchedule(CreateMeetingScheduleRequest schedule) {
		return new CreateMeetingRequest(
			"저녁 모임",
			"같이 밥 먹어요",
			MeetingType.one_time,
			new LocationSnapshot(37.5, 127.0, "서울특별시 강남구 테헤란로 123", "2번 출구 앞", "동선역 2번 출구"),
			schedule,
			null,
			7,
			null
		);
	}

	private CreateMeetingRecurrenceRuleRequest weeklyRule(LocalDate startsOn) {
		return new CreateMeetingRecurrenceRuleRequest(
			RecurrenceFrequency.weekly,
			1,
			List.of(2),
			null,
			startsOn,
			LocalDate.parse("2026-07-21"),
			null,
			"Asia/Seoul"
		);
	}

	private void allowActiveSchedule(Long meetingId) {
		when(meetingScheduleRepository.existsActiveSchedule(eq(meetingId), any(OffsetDateTime.class)))
			.thenReturn(true);
	}

	private Meeting meeting(Long id, Long hostId, OffsetDateTime meetingAt, int maxMembers) {
		return meeting(id, hostId, MeetingType.one_time, meetingAt, maxMembers);
	}

	private Meeting meeting(Long id, Long hostId, MeetingType type, OffsetDateTime meetingAt, int maxMembers) {
		Meeting meeting = Meeting.create(
			11L,
			hostId,
			type,
			"저녁 모임",
			"같이 밥 먹어요",
			meetingAt,
			maxMembers,
			null,
			null
		);
		setField(meeting, "id", id);
		return meeting;
	}

	private AuthenticatedUser principal(Long userId) {
		return new AuthenticatedUser(userId, "user" + userId + "@example.com", UserRole.user, UserStatus.active);
	}

	private AuthenticatedUser adminPrincipal(Long userId) {
		return new AuthenticatedUser(userId, "admin" + userId + "@example.com", UserRole.admin, UserStatus.active);
	}

	private MeetingDetailProjection detailRow(
		UUID hostProfileFileId,
		UUID imageFileId,
		OffsetDateTime meetingAt,
		OffsetDateTime createdAt
	) {
		return detailRow(hostProfileFileId, imageFileId, meetingAt, createdAt, "recurring");
	}

	private MeetingDetailProjection detailRow(
		UUID hostProfileFileId,
		UUID imageFileId,
		OffsetDateTime meetingAt,
		OffsetDateTime createdAt,
		String type
	) {
		return new MeetingDetailProjection() {
			@Override
			public Long getMeetingId() {
				return 3L;
			}

			@Override
			public Long getPinId() {
				return 11L;
			}

			@Override
			public Long getRoomId() {
				return 9L;
			}

			@Override
			public String getTitle() {
				return "저녁 모임";
			}

			@Override
			public String getContent() {
				return "같이 밥 먹어요";
			}

			@Override
			public Instant getMeetingAt() {
				return meetingAt == null ? null : meetingAt.toInstant();
			}

			@Override
			public String getType() {
				return type;
			}

			@Override
			public String getStatus() {
				return "open";
			}

			@Override
			public int getMaxMembers() {
				return 7;
			}

			@Override
			public Long getHostUserId() {
				return 1L;
			}

			@Override
			public String getHostNickname() {
				return "오이정";
			}

			@Override
			public UUID getHostProfileFileId() {
				return hostProfileFileId;
			}

			@Override
			public UUID getImageFileId() {
				return imageFileId;
			}

			@Override
			public UUID getThumbnailFileId() {
				return imageFileId;
			}

			@Override
			public double getLatitude() {
				return 37.5;
			}

			@Override
			public double getLongitude() {
				return 127.0;
			}

			@Override
			public String getAddress() {
				return "서울특별시 강남구 테헤란로 123";
			}

			@Override
			public String getDetailAddress() {
				return "2번 출구 앞";
			}

			@Override
			public String getLabel() {
				return "동선역 2번 출구";
			}

			@Override
			public Instant getCreatedAt() {
				return createdAt.toInstant();
			}
		};
	}

	private MeetingParticipantProjection participantRow(
		Long userId,
		String nickname,
		UUID profileFileId,
		OffsetDateTime joinedAt
	) {
		return new MeetingParticipantProjection() {
			@Override
			public Long getUserId() {
				return userId;
			}

			@Override
			public String getNickname() {
				return nickname;
			}

			@Override
			public UUID getProfileFileId() {
				return profileFileId;
			}

			@Override
			public Instant getJoinedAt() {
				return joinedAt.toInstant();
			}
		};
	}

	private MeetingCalendarProjection calendarRow() {
		return new MeetingCalendarProjection() {
			@Override
			public Long getMeetingId() {
				return 3L;
			}

			@Override
			public Long getScheduleId() {
				return 31L;
			}

			@Override
			public String getTitle() {
				return "저녁 모임";
			}

			@Override
			public double getLatitude() {
				return 37.5;
			}

			@Override
			public double getLongitude() {
				return 127.0;
			}

			@Override
			public String getAddress() {
				return "서울특별시 강남구 테헤란로 123";
			}

			@Override
			public String getDetailAddress() {
				return "2번 출구 앞";
			}

			@Override
			public String getLabel() {
				return "동선역 2번 출구";
			}
			@Override
			public Instant getStartsAt() {
				return OffsetDateTime.parse("2099-07-10T19:00:00+09:00").toInstant();
			}

			@Override
			public Instant getEndsAt() {
				return OffsetDateTime.parse("2099-07-10T20:00:00+09:00").toInstant();
			}

			@Override
			public String getStatus() {
				return "scheduled";
			}

			@Override
			public Long getCreatedByUserId() {
				return 42L;
			}

			@Override
			public Long getRoomId() {
				return 9L;
			}

			@Override
			public Boolean getHost() {
				return false;
			}
		};
	}

	private MeetingCalendarProjection unscheduledCalendarRow() {
		return new MeetingCalendarProjection() {
			@Override
			public Long getMeetingId() {
				return 4L;
			}

			@Override
			public Long getScheduleId() {
				return null;
			}

			@Override
			public String getTitle() {
				return "일정 미정 모임";
			}

			@Override
			public double getLatitude() {
				return 37.5;
			}

			@Override
			public double getLongitude() {
				return 127.0;
			}

			@Override
			public String getAddress() {
				return "서울";
			}

			@Override
			public String getDetailAddress() {
				return "";
			}

			@Override
			public String getLabel() {
				return "";
			}

			@Override
			public Instant getStartsAt() {
				return null;
			}

			@Override
			public Instant getEndsAt() {
				return null;
			}

			@Override
			public String getStatus() {
				return "unscheduled";
			}

			@Override
			public Long getCreatedByUserId() {
				return null;
			}

			@Override
			public Long getRoomId() {
				return 10L;
			}

			@Override
			public Boolean getHost() {
				return true;
			}
		};
	}

	private File uploadedFile(UUID fileId, Long uploaderId, String contentType) {
		File file = File.pending(fileId, uploaderId, "tmp/%s".formatted(fileId), contentType, 100L);
		file.markUploaded(OffsetDateTime.parse("2026-07-09T10:00:00+09:00"), contentType, 100L);
		return file;
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
