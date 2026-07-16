package shinhan.fibri.ieum.main.report.service;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.meeting.domain.Meeting;
import shinhan.fibri.ieum.main.meeting.domain.MeetingParticipant;
import shinhan.fibri.ieum.main.meeting.domain.MeetingSchedule;
import shinhan.fibri.ieum.main.meeting.domain.MeetingScheduleStatus;
import shinhan.fibri.ieum.main.meeting.domain.ParticipantStatus;
import shinhan.fibri.ieum.main.meeting.exception.KickedMemberException;
import shinhan.fibri.ieum.main.meeting.exception.NotMeetingMemberException;
import shinhan.fibri.ieum.main.meeting.exception.ScheduleNotFoundException;
import shinhan.fibri.ieum.main.meeting.exception.SchedulePermissionDeniedException;
import shinhan.fibri.ieum.main.meeting.repository.MeetingParticipantRepository;
import shinhan.fibri.ieum.main.meeting.repository.MeetingRepository;
import shinhan.fibri.ieum.main.meeting.repository.MeetingScheduleRepository;
import shinhan.fibri.ieum.main.report.domain.Report;
import shinhan.fibri.ieum.main.report.domain.ReportContextSnapshot;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.main.report.dto.CreateReportResponse;
import shinhan.fibri.ieum.main.report.repository.ReportRepository;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

@Service
public class MeetingScheduleReportService {

	private final MeetingRepository meetingRepository;
	private final MeetingScheduleRepository scheduleRepository;
	private final MeetingParticipantRepository participantRepository;
	private final UserRepository userRepository;
	private final ReportRepository reportRepository;
	private final ReportContextSnapshotFactory snapshotFactory;

	public MeetingScheduleReportService(
		MeetingRepository meetingRepository,
		MeetingScheduleRepository scheduleRepository,
		MeetingParticipantRepository participantRepository,
		UserRepository userRepository,
		ReportRepository reportRepository,
		ReportContextSnapshotFactory snapshotFactory
	) {
		this.meetingRepository = meetingRepository;
		this.scheduleRepository = scheduleRepository;
		this.participantRepository = participantRepository;
		this.userRepository = userRepository;
		this.reportRepository = reportRepository;
		this.snapshotFactory = snapshotFactory;
	}

	@Transactional
	public CreateReportResponse create(
		AuthenticatedUser principal,
		Long meetingId,
		Long scheduleId,
		ReportReason reason,
		String detail
	) {
		Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
			.orElseThrow(shinhan.fibri.ieum.main.meeting.exception.MeetingNotFoundException::new);
		ensureMeetingAccess(principal, meeting);
		MeetingSchedule schedule = scheduleRepository.findByIdAndMeetingIdAndDeletedAtIsNull(scheduleId, meetingId)
			.orElseThrow(ScheduleNotFoundException::new);
		if (schedule.getStatus() != MeetingScheduleStatus.scheduled || !schedule.getStartsAt().isAfter(OffsetDateTime.now())) {
			throw new ScheduleNotFoundException();
		}
		if (Objects.equals(schedule.getCreatedBy(), principal.userId())) {
			throw new SchedulePermissionDeniedException();
		}
		Long ownerId = schedule.getCreatedBy();
		if (ownerId == null) {
			throw new ScheduleNotFoundException();
		}
		User reporter = userRepository.findByIdAndDeletedAtIsNull(principal.userId())
			.orElseThrow(UserNotFoundException::new);
		User owner = userRepository.getReferenceById(ownerId);
		ReportContextSnapshot snapshot = snapshotFactory.createSchedule(schedule);
		Report report = reportRepository.save(Report.scheduleReport(reporter, schedule, owner, reason, detail, snapshot));
		return new CreateReportResponse(report.getId());
	}

	private void ensureMeetingAccess(AuthenticatedUser principal, Meeting meeting) {
		if (meeting.getHostId().equals(principal.userId()) || principal.role() == UserRole.admin) {
			return;
		}
		Optional<MeetingParticipant> participant = participantRepository.findByIdMeetingIdAndIdUserId(
			meeting.getId(),
			principal.userId()
		);
		if (participant.map(row -> row.getStatus() == ParticipantStatus.kicked).orElse(false)) {
			throw new KickedMemberException();
		}
		if (participant.map(row -> row.getStatus() == ParticipantStatus.joined).orElse(false)) {
			return;
		}
		throw new NotMeetingMemberException();
	}
}
