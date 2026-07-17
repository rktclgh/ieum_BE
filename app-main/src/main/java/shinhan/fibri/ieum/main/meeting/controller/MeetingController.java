package shinhan.fibri.ieum.main.meeting.controller;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingRequest;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingResponse;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingScheduleResponse;
import shinhan.fibri.ieum.main.meeting.dto.JoinMeetingResponse;
import shinhan.fibri.ieum.main.meeting.dto.KickMeetingRequest;
import shinhan.fibri.ieum.main.meeting.dto.MeetingCalendarResponse;
import shinhan.fibri.ieum.main.meeting.dto.MeetingDetailResponse;
import shinhan.fibri.ieum.main.meeting.dto.MeetingParticipantsResponse;
import shinhan.fibri.ieum.main.meeting.dto.MeetingSchedulesResponse;
import shinhan.fibri.ieum.main.meeting.dto.ManageMeetingScheduleRequest;
import shinhan.fibri.ieum.main.meeting.dto.MeetingScheduleItem;
import shinhan.fibri.ieum.main.meeting.service.MeetingService;
import shinhan.fibri.ieum.main.report.dto.CreateReportResponse;
import shinhan.fibri.ieum.main.report.dto.CreateScheduleReportRequest;
import shinhan.fibri.ieum.main.report.service.MeetingScheduleReportService;

@RestController
@RequestMapping("/api/v1/meetings")
@RequiredArgsConstructor
public class MeetingController {

	private final MeetingService meetingService;
	private final MeetingScheduleReportService scheduleReportService;

	@PostMapping
	public ResponseEntity<CreateMeetingResponse> create(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@Valid @RequestBody CreateMeetingRequest request
	) {
		CreateMeetingResponse response = meetingService.create(principal, request);
		return ResponseEntity.created(URI.create("/api/v1/meetings/" + response.meetingId()))
			.body(response);
	}

	@GetMapping("/calendar")
	public ResponseEntity<MeetingCalendarResponse> getCalendar(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@RequestParam(required = false) OffsetDateTime from,
		@RequestParam(required = false) OffsetDateTime to
	) {
		return ResponseEntity.ok(meetingService.getCalendar(principal, from, to));
	}

	@GetMapping("/{meetingId}")
	public ResponseEntity<MeetingDetailResponse> getDetail(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long meetingId
	) {
		return ResponseEntity.ok(meetingService.getDetail(principal, meetingId));
	}

	@GetMapping("/{meetingId}/participants")
	public ResponseEntity<MeetingParticipantsResponse> getParticipants(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long meetingId
	) {
		return ResponseEntity.ok(meetingService.getParticipants(principal, meetingId));
	}

	@GetMapping("/{meetingId}/schedules")
	public ResponseEntity<MeetingSchedulesResponse> getSchedules(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long meetingId,
		@RequestParam(required = false) OffsetDateTime from,
		@RequestParam(required = false) OffsetDateTime to
	) {
		return ResponseEntity.ok(meetingService.getSchedules(principal, meetingId, from, to));
	}

	@PostMapping("/{meetingId}/join")
	public ResponseEntity<JoinMeetingResponse> join(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long meetingId
	) {
		return ResponseEntity.ok(meetingService.join(principal, meetingId));
	}

	@PostMapping("/{meetingId}/schedules")
	public ResponseEntity<CreateMeetingScheduleResponse> addSchedule(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long meetingId,
		@Valid @RequestBody ManageMeetingScheduleRequest request
	) {
		CreateMeetingScheduleResponse response = meetingService.addManagedSchedule(principal, meetingId, request);
		return ResponseEntity.created(URI.create(
				"/api/v1/meetings/" + meetingId + "/schedules/" + response.scheduleId()
			))
			.body(response);
	}

	@PatchMapping("/{meetingId}/schedules/{scheduleId}")
	public ResponseEntity<MeetingScheduleItem> updateSchedule(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long meetingId,
		@PathVariable Long scheduleId,
		@Valid @RequestBody ManageMeetingScheduleRequest request
	) {
		return ResponseEntity.ok(meetingService.updateManagedSchedule(principal, meetingId, scheduleId, request));
	}

	@PostMapping("/{meetingId}/schedules/{scheduleId}/report")
	public ResponseEntity<CreateReportResponse> reportSchedule(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long meetingId,
		@PathVariable Long scheduleId,
		@Valid @RequestBody CreateScheduleReportRequest request
	) {
		CreateReportResponse response = scheduleReportService.create(
			principal,
			meetingId,
			scheduleId,
			request.reason(),
			request.detail()
		);
		return ResponseEntity.created(URI.create("/api/v1/reports/" + response.reportId())).body(response);
	}

	@DeleteMapping("/{meetingId}/schedules/{scheduleId}")
	public ResponseEntity<Void> cancelSchedule(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long meetingId,
		@PathVariable Long scheduleId
	) {
		meetingService.cancelSchedule(principal, meetingId, scheduleId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{meetingId}/leave")
	public ResponseEntity<Void> leave(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long meetingId
	) {
		meetingService.leave(principal, meetingId);
		return ResponseEntity.ok().build();
	}

	@PostMapping("/{meetingId}/kick")
	public ResponseEntity<Void> kick(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long meetingId,
		@Valid @RequestBody KickMeetingRequest request
	) {
		meetingService.kick(principal, meetingId, request);
		return ResponseEntity.ok().build();
	}

	@PostMapping("/{meetingId}/close")
	public ResponseEntity<Void> close(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long meetingId
	) {
		meetingService.close(principal, meetingId);
		return ResponseEntity.ok().build();
	}

	@DeleteMapping("/{meetingId}")
	public ResponseEntity<Void> delete(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long meetingId
	) {
		meetingService.cancel(principal, meetingId);
		return ResponseEntity.noContent().build();
	}
}
