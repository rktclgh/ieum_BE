package shinhan.fibri.ieum.main.meeting.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingResponse;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingScheduleResponse;
import shinhan.fibri.ieum.main.meeting.dto.JoinMeetingResponse;
import shinhan.fibri.ieum.main.meeting.dto.KickMeetingRequest;
import shinhan.fibri.ieum.main.meeting.dto.MeetingCalendarItem;
import shinhan.fibri.ieum.main.meeting.dto.MeetingCalendarResponse;
import shinhan.fibri.ieum.main.meeting.dto.MeetingDetailRecurrenceRuleResponse;
import shinhan.fibri.ieum.main.meeting.dto.MeetingDetailResponse;
import shinhan.fibri.ieum.main.meeting.dto.MeetingHostSummary;
import shinhan.fibri.ieum.main.meeting.dto.MeetingParticipantItem;
import shinhan.fibri.ieum.main.meeting.dto.MeetingParticipantsResponse;
import shinhan.fibri.ieum.main.meeting.dto.MeetingScheduleItem;
import shinhan.fibri.ieum.main.meeting.dto.MeetingSchedulesResponse;
import shinhan.fibri.ieum.main.meeting.exception.HostCannotLeaveException;
import shinhan.fibri.ieum.main.meeting.exception.InvalidMeetingRequestException;
import shinhan.fibri.ieum.main.meeting.exception.KickedMemberException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingFullException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingNotFoundException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingNotOpenException;
import shinhan.fibri.ieum.main.meeting.exception.NotHostException;
import shinhan.fibri.ieum.main.meeting.exception.NotMeetingMemberException;
import shinhan.fibri.ieum.main.meeting.exception.ParticipantNotFoundException;
import shinhan.fibri.ieum.main.meeting.exception.ScheduleAlreadyExistsException;
import shinhan.fibri.ieum.main.meeting.exception.ScheduleNotCancellableException;
import shinhan.fibri.ieum.main.meeting.exception.ScheduleNotFoundException;
import shinhan.fibri.ieum.main.meeting.service.MeetingService;
import shinhan.fibri.ieum.main.pin.dto.LocationSnapshot;

@WebMvcTest(MeetingController.class)
@AutoConfigureMockMvc(addFilters = false)
class MeetingControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MeetingService meetingService;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
		reset(meetingService);
	}

	@Test
	void createReturnsCreatedMeetingIds() throws Exception {
		when(meetingService.create(any(AuthenticatedUser.class), any()))
			.thenReturn(new CreateMeetingResponse(3L, 11L, 9L, 31L));

		mockMvc.perform(post("/api/v1/meetings")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "저녁 모임",
					  "content": "같이 밥 먹어요",
					  "type": "one_time",
					  "location": {
					    "lat": 37.5,
					    "lng": 127.0,
					    "address": "서울특별시 강남구 테헤란로 123",
					    "detailAddress": "2번 출구 앞",
					    "label": "동선역 2번 출구"
					  },
					  "schedule": { "startsAt": "2099-07-10T19:00:00+09:00" },
					  "maxMembers": 7,
					  "imageFileId": "00000000-0000-0000-0000-000000000001"
					}
					""")
				.with(authenticated()))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/meetings/3"))
			.andExpect(jsonPath("$.meetingId", is(3)))
			.andExpect(jsonPath("$.pinId", is(11)))
			.andExpect(jsonPath("$.roomId", is(9)))
			.andExpect(jsonPath("$.firstScheduleId", is(31)));

		verify(meetingService).create(any(AuthenticatedUser.class), any());
	}

	@Test
	void createValidatesRequiredFields() throws Exception {
		mockMvc.perform(post("/api/v1/meetings")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}")
				.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("location")));
	}

	@Test
	void mapsInvalidMeetingRequestToValidationFailed() throws Exception {
		when(meetingService.create(any(AuthenticatedUser.class), any()))
			.thenThrow(new InvalidMeetingRequestException("VALIDATION_FAILED", "imageFileId", "Invalid image"));

		mockMvc.perform(post("/api/v1/meetings")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "저녁 모임",
					  "type": "one_time",
					  "location": {
					    "lat": 37.5,
					    "lng": 127.0,
					    "address": "서울특별시 강남구 테헤란로 123",
					    "detailAddress": "2번 출구 앞",
					    "label": "동선역 2번 출구"
					  },
					  "schedule": { "startsAt": "2099-07-10T19:00:00+09:00" },
					  "maxMembers": 7,
					  "imageFileId": "00000000-0000-0000-0000-000000000001"
					}
					""")
				.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("imageFileId")));
	}

	@Test
	void getDetailReturnsMeetingDetail() throws Exception {
		when(meetingService.getDetail(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L)))
			.thenReturn(new MeetingDetailResponse(
				3L,
				11L,
				9L,
				"저녁 모임",
				"같이 밥 먹어요",
				OffsetDateTime.parse("2026-07-10T19:00:00+09:00"),
				"recurring",
				true,
				new MeetingScheduleItem(
					32L,
					OffsetDateTime.parse("2026-07-14T19:00:00+09:00"),
					OffsetDateTime.parse("2026-07-14T20:00:00+09:00"),
					"scheduled"
				),
				new MeetingDetailRecurrenceRuleResponse(
					"weekly",
					1,
					java.util.List.of(2),
					null,
					java.time.LocalDate.parse("2026-07-07"),
					java.time.LocalDate.parse("2026-07-21"),
					3,
					"Asia/Seoul"
				),
				"open",
				7,
				7L,
				new MeetingHostSummary(1L, "오이정", "/api/v1/files/11111111-1111-1111-1111-111111111111"),
				"/api/v1/files/22222222-2222-2222-2222-222222222222?v=display",
				"/api/v1/files/22222222-2222-2222-2222-222222222222?v=thumb",
				new LocationSnapshot(37.5, 127.0, "서울특별시 강남구 테헤란로 123", "2번 출구 앞", "동선역 2번 출구"),
				"joined",
				OffsetDateTime.parse("2026-07-09T10:00:00+09:00")
			));

		mockMvc.perform(get("/api/v1/meetings/{meetingId}", 3L)
				.with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.meetingId", is(3)))
			.andExpect(jsonPath("$.pinId", is(11)))
			.andExpect(jsonPath("$.roomId", is(9)))
			.andExpect(jsonPath("$.title", is("저녁 모임")))
			.andExpect(jsonPath("$.type", is("recurring")))
			.andExpect(jsonPath("$.active", is(true)))
			.andExpect(jsonPath("$.nextSchedule.scheduleId", is(32)))
			.andExpect(jsonPath("$.nextSchedule.startsAt", is("2026-07-14T19:00:00+09:00")))
			.andExpect(jsonPath("$.recurrenceRule.frequency", is("weekly")))
			.andExpect(jsonPath("$.recurrenceRule.daysOfWeek[0]", is(2)))
			.andExpect(jsonPath("$.status", is("open")))
			.andExpect(jsonPath("$.participantCount", is(7)))
			.andExpect(jsonPath("$.host.userId", is(1)))
			.andExpect(jsonPath("$.host.nickname", is("오이정")))
			.andExpect(jsonPath("$.imageUrl", is("/api/v1/files/22222222-2222-2222-2222-222222222222?v=display")))
			.andExpect(jsonPath("$.thumbnailUrl", is("/api/v1/files/22222222-2222-2222-2222-222222222222?v=thumb")))
			.andExpect(jsonPath("$.location.lat", is(37.5)))
			.andExpect(jsonPath("$.location.lng", is(127.0)))
			.andExpect(jsonPath("$.location.address", is("서울특별시 강남구 테헤란로 123")))
			.andExpect(jsonPath("$.myStatus", is("joined")));
	}

	@Test
	void getDetailMapsMissingMeetingToNotFound() throws Exception {
		when(meetingService.getDetail(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L)))
			.thenThrow(new MeetingNotFoundException());

		mockMvc.perform(get("/api/v1/meetings/{meetingId}", 3L)
				.with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("MEETING_NOT_FOUND")));
	}

	@Test
	void getParticipantsReturnsJoinedParticipants() throws Exception {
		when(meetingService.getParticipants(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L)))
			.thenReturn(new MeetingParticipantsResponse(java.util.List.of(
				new MeetingParticipantItem(
					1L,
					"오이정",
					null,
					true,
					OffsetDateTime.parse("2026-07-09T10:00:00+09:00")
				),
				new MeetingParticipantItem(
					42L,
					"참여자",
					"/api/v1/files/33333333-3333-3333-3333-333333333333",
					false,
					OffsetDateTime.parse("2026-07-09T11:00:00+09:00")
				)
			)));

		mockMvc.perform(get("/api/v1/meetings/{meetingId}/participants", 3L)
				.with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].userId", is(1)))
			.andExpect(jsonPath("$.items[0].nickname", is("오이정")))
			.andExpect(jsonPath("$.items[0].profileImageUrl").doesNotExist())
			.andExpect(jsonPath("$.items[0].isHost", is(true)))
			.andExpect(jsonPath("$.items[0].joinedAt", is("2026-07-09T10:00:00+09:00")))
			.andExpect(jsonPath("$.items[1].userId", is(42)))
			.andExpect(jsonPath("$.items[1].profileImageUrl", is("/api/v1/files/33333333-3333-3333-3333-333333333333")))
			.andExpect(jsonPath("$.items[1].isHost", is(false)));
	}

	@Test
	void getParticipantsMapsMissingMeetingToNotFound() throws Exception {
		when(meetingService.getParticipants(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L)))
			.thenThrow(new MeetingNotFoundException());

		mockMvc.perform(get("/api/v1/meetings/{meetingId}/participants", 3L)
				.with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("MEETING_NOT_FOUND")));
	}

	@Test
	void getSchedulesReturnsMeetingSchedules() throws Exception {
		when(meetingService.getSchedules(
			any(AuthenticatedUser.class),
			org.mockito.ArgumentMatchers.eq(3L),
			any(),
			any()
		))
			.thenReturn(new MeetingSchedulesResponse(java.util.List.of(
				new MeetingScheduleItem(
					31L,
					OffsetDateTime.parse("2099-07-10T19:00:00+09:00"),
					OffsetDateTime.parse("2099-07-10T20:00:00+09:00"),
					"scheduled"
				)
			)));

		mockMvc.perform(get("/api/v1/meetings/{meetingId}/schedules", 3L)
				.param("from", "2099-07-01T00:00:00+09:00")
				.param("to", "2099-08-01T00:00:00+09:00")
				.with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].scheduleId", is(31)))
			.andExpect(jsonPath("$.items[0].startsAt", is("2099-07-10T19:00:00+09:00")))
			.andExpect(jsonPath("$.items[0].status", is("scheduled")));
	}

	@Test
	void getSchedulesMapsNotMeetingMemberToForbidden() throws Exception {
		when(meetingService.getSchedules(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L), any(), any()))
			.thenThrow(new NotMeetingMemberException());

		mockMvc.perform(get("/api/v1/meetings/{meetingId}/schedules", 3L)
				.with(authenticated()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("NOT_MEETING_MEMBER")));
	}

	@Test
	void getCalendarReturnsMyMeetingSchedules() throws Exception {
		when(meetingService.getCalendar(any(AuthenticatedUser.class), any(), any()))
			.thenReturn(new MeetingCalendarResponse(java.util.List.of(
				new MeetingCalendarItem(
					3L,
					31L,
					"저녁 모임",
					new LocationSnapshot(37.5, 127.0, "서울특별시 강남구 테헤란로 123", "2번 출구 앞", "동선역 2번 출구"),
					OffsetDateTime.parse("2099-07-10T19:00:00+09:00"),
					OffsetDateTime.parse("2099-07-10T20:00:00+09:00"),
					"scheduled",
					9L,
					true
				)
			)));

		mockMvc.perform(get("/api/v1/meetings/calendar")
				.param("from", "2099-07-01T00:00:00+09:00")
				.param("to", "2099-08-01T00:00:00+09:00")
				.with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].meetingId", is(3)))
			.andExpect(jsonPath("$.items[0].scheduleId", is(31)))
			.andExpect(jsonPath("$.items[0].title", is("저녁 모임")))
			.andExpect(jsonPath("$.items[0].roomId", is(9)))
			.andExpect(jsonPath("$.items[0].isHost", is(true)));
	}

	@Test
	void joinReturnsGroupRoomId() throws Exception {
		when(meetingService.join(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L)))
			.thenReturn(new JoinMeetingResponse(9L));

		mockMvc.perform(post("/api/v1/meetings/{meetingId}/join", 3L)
				.with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.roomId", is(9)));
	}

	@Test
	void joinMapsMeetingNotOpenToConflict() throws Exception {
		when(meetingService.join(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L)))
			.thenThrow(new MeetingNotOpenException());

		mockMvc.perform(post("/api/v1/meetings/{meetingId}/join", 3L)
				.with(authenticated()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("MEETING_NOT_OPEN")));
	}

	@Test
	void joinMapsMeetingFullToConflict() throws Exception {
		when(meetingService.join(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L)))
			.thenThrow(new MeetingFullException());

		mockMvc.perform(post("/api/v1/meetings/{meetingId}/join", 3L)
				.with(authenticated()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("MEETING_FULL")));
	}

	@Test
	void joinMapsKickedMemberToForbidden() throws Exception {
		when(meetingService.join(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L)))
			.thenThrow(new KickedMemberException());

		mockMvc.perform(post("/api/v1/meetings/{meetingId}/join", 3L)
				.with(authenticated()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("KICKED_MEMBER")));
	}

	@Test
	void addScheduleReturnsCreatedScheduleId() throws Exception {
		when(meetingService.addSchedule(
			any(AuthenticatedUser.class),
			org.mockito.ArgumentMatchers.eq(3L),
			any()
		))
			.thenReturn(new CreateMeetingScheduleResponse(32L));

		mockMvc.perform(post("/api/v1/meetings/{meetingId}/schedules", 3L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "startsAt": "2099-07-10T19:00:00+09:00",
					  "endsAt": "2099-07-10T20:00:00+09:00"
					}
					""")
				.with(authenticated()))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/meetings/3/schedules/32"))
			.andExpect(jsonPath("$.scheduleId", is(32)));
	}

	@Test
	void addScheduleMapsScheduleAlreadyExistsToConflict() throws Exception {
		when(meetingService.addSchedule(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L), any()))
			.thenThrow(new ScheduleAlreadyExistsException());

		mockMvc.perform(post("/api/v1/meetings/{meetingId}/schedules", 3L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"startsAt\":\"2099-07-10T19:00:00+09:00\"}")
				.with(authenticated()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("SCHEDULE_ALREADY_EXISTS")));
	}

	@Test
	void addScheduleMapsNotHostToForbidden() throws Exception {
		when(meetingService.addSchedule(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L), any()))
			.thenThrow(new NotHostException());

		mockMvc.perform(post("/api/v1/meetings/{meetingId}/schedules", 3L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"startsAt\":\"2099-07-10T19:00:00+09:00\"}")
				.with(authenticated()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("NOT_HOST")));
	}

	@Test
	void deleteScheduleReturnsNoContent() throws Exception {
		mockMvc.perform(delete("/api/v1/meetings/{meetingId}/schedules/{scheduleId}", 3L, 31L)
				.with(authenticated()))
			.andExpect(status().isNoContent());

		verify(meetingService).cancelSchedule(
			any(AuthenticatedUser.class),
			org.mockito.ArgumentMatchers.eq(3L),
			org.mockito.ArgumentMatchers.eq(31L)
		);
	}

	@Test
	void deleteScheduleMapsScheduleNotFoundToNotFound() throws Exception {
		org.mockito.Mockito.doThrow(new ScheduleNotFoundException())
			.when(meetingService).cancelSchedule(
				any(AuthenticatedUser.class),
				org.mockito.ArgumentMatchers.eq(3L),
				org.mockito.ArgumentMatchers.eq(31L)
			);

		mockMvc.perform(delete("/api/v1/meetings/{meetingId}/schedules/{scheduleId}", 3L, 31L)
				.with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("SCHEDULE_NOT_FOUND")));
	}

	@Test
	void deleteScheduleMapsScheduleNotCancellableToConflict() throws Exception {
		org.mockito.Mockito.doThrow(new ScheduleNotCancellableException())
			.when(meetingService).cancelSchedule(
				any(AuthenticatedUser.class),
				org.mockito.ArgumentMatchers.eq(3L),
				org.mockito.ArgumentMatchers.eq(31L)
			);

		mockMvc.perform(delete("/api/v1/meetings/{meetingId}/schedules/{scheduleId}", 3L, 31L)
				.with(authenticated()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("SCHEDULE_NOT_CANCELLABLE")));
	}

	@Test
	void leaveReturnsOk() throws Exception {
		mockMvc.perform(post("/api/v1/meetings/{meetingId}/leave", 3L)
				.with(authenticated()))
			.andExpect(status().isOk());

		verify(meetingService).leave(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L));
	}

	@Test
	void leaveMapsHostCannotLeaveToForbidden() throws Exception {
		org.mockito.Mockito.doThrow(new HostCannotLeaveException())
			.when(meetingService).leave(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L));

		mockMvc.perform(post("/api/v1/meetings/{meetingId}/leave", 3L)
				.with(authenticated()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("HOST_CANNOT_LEAVE")));
	}

	@Test
	void leaveMapsParticipantNotFoundToNotFound() throws Exception {
		org.mockito.Mockito.doThrow(new ParticipantNotFoundException())
			.when(meetingService).leave(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L));

		mockMvc.perform(post("/api/v1/meetings/{meetingId}/leave", 3L)
				.with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("PARTICIPANT_NOT_FOUND")));
	}

	@Test
	void kickReturnsOk() throws Exception {
		mockMvc.perform(post("/api/v1/meetings/{meetingId}/kick", 3L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":42}")
				.with(authenticated()))
			.andExpect(status().isOk());

		verify(meetingService).kick(
			any(AuthenticatedUser.class),
			org.mockito.ArgumentMatchers.eq(3L),
			org.mockito.ArgumentMatchers.eq(new KickMeetingRequest(42L))
		);
	}

	@Test
	void kickValidatesUserId() throws Exception {
		mockMvc.perform(post("/api/v1/meetings/{meetingId}/kick", 3L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}")
				.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("userId")));
	}

	@Test
	void kickMapsNotHostToForbidden() throws Exception {
		org.mockito.Mockito.doThrow(new NotHostException())
			.when(meetingService).kick(
				any(AuthenticatedUser.class),
				org.mockito.ArgumentMatchers.eq(3L),
				any(KickMeetingRequest.class)
			);

		mockMvc.perform(post("/api/v1/meetings/{meetingId}/kick", 3L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":42}")
				.with(authenticated()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("NOT_HOST")));
	}

	@Test
	void kickMapsParticipantNotFoundToNotFound() throws Exception {
		org.mockito.Mockito.doThrow(new ParticipantNotFoundException())
			.when(meetingService).kick(
				any(AuthenticatedUser.class),
				org.mockito.ArgumentMatchers.eq(3L),
				any(KickMeetingRequest.class)
			);

		mockMvc.perform(post("/api/v1/meetings/{meetingId}/kick", 3L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":42}")
				.with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("PARTICIPANT_NOT_FOUND")));
	}

	@Test
	void closeReturnsOk() throws Exception {
		mockMvc.perform(post("/api/v1/meetings/{meetingId}/close", 3L)
				.with(authenticated()))
			.andExpect(status().isOk());

		verify(meetingService).close(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L));
	}

	@Test
	void closeMapsNotHostToForbidden() throws Exception {
		org.mockito.Mockito.doThrow(new NotHostException())
			.when(meetingService).close(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L));

		mockMvc.perform(post("/api/v1/meetings/{meetingId}/close", 3L)
				.with(authenticated()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("NOT_HOST")));
	}

	@Test
	void closeMapsMeetingNotOpenToConflict() throws Exception {
		org.mockito.Mockito.doThrow(new MeetingNotOpenException())
			.when(meetingService).close(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L));

		mockMvc.perform(post("/api/v1/meetings/{meetingId}/close", 3L)
				.with(authenticated()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("MEETING_NOT_OPEN")));
	}

	@Test
	void deleteReturnsNoContent() throws Exception {
		mockMvc.perform(delete("/api/v1/meetings/{meetingId}", 3L)
				.with(authenticated()))
			.andExpect(status().isNoContent());

		verify(meetingService).cancel(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L));
	}

	@Test
	void deleteMapsNotHostToForbidden() throws Exception {
		org.mockito.Mockito.doThrow(new NotHostException())
			.when(meetingService).cancel(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L));

		mockMvc.perform(delete("/api/v1/meetings/{meetingId}", 3L)
				.with(authenticated()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("NOT_HOST")));
	}

	@Test
	void deleteMapsMeetingNotFoundToNotFound() throws Exception {
		org.mockito.Mockito.doThrow(new MeetingNotFoundException())
			.when(meetingService).cancel(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(3L));

		mockMvc.perform(delete("/api/v1/meetings/{meetingId}", 3L)
				.with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("MEETING_NOT_FOUND")));
	}

	private static RequestPostProcessor authenticated() {
		return request -> {
			AuthenticatedUser principal = new AuthenticatedUser(
				42L,
				"user@example.com",
				UserRole.user,
				UserStatus.active
			);
			SecurityContextHolder.getContext()
				.setAuthentication(new TestingAuthenticationToken(principal, null));
			return request;
		};
	}

	@TestConfiguration
	static class TestConfig implements WebMvcConfigurer {

		@Override
		public void addArgumentResolvers(java.util.List<HandlerMethodArgumentResolver> resolvers) {
			resolvers.add(new AuthenticationPrincipalArgumentResolver());
		}

		@Bean
		@Primary
		MeetingService meetingService() {
			return mock(MeetingService.class);
		}

		@Bean
		@Primary
		SessionTokenValidator sessionTokenValidator() {
			return mock(SessionTokenValidator.class);
		}
	}
}
