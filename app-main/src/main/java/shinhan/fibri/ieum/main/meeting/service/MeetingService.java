package shinhan.fibri.ieum.main.meeting.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.file.domain.File;
import shinhan.fibri.ieum.common.file.repository.FileRepository;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.chat.service.ChatRoomLifecycle;
import shinhan.fibri.ieum.main.meeting.domain.Meeting;
import shinhan.fibri.ieum.main.meeting.domain.MeetingRecurrenceRule;
import shinhan.fibri.ieum.main.meeting.domain.MeetingParticipant;
import shinhan.fibri.ieum.main.meeting.domain.MeetingSchedule;
import shinhan.fibri.ieum.main.meeting.domain.MeetingStatus;
import shinhan.fibri.ieum.main.meeting.domain.MeetingType;
import shinhan.fibri.ieum.main.meeting.domain.ParticipantStatus;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingRecurrenceRuleRequest;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingRequest;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingResponse;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingScheduleRequest;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingScheduleResponse;
import shinhan.fibri.ieum.main.meeting.dto.JoinMeetingResponse;
import shinhan.fibri.ieum.main.meeting.dto.KickMeetingRequest;
import shinhan.fibri.ieum.main.meeting.dto.MeetingDetailResponse;
import shinhan.fibri.ieum.main.meeting.dto.MeetingHostSummary;
import shinhan.fibri.ieum.main.meeting.dto.MeetingLocation;
import shinhan.fibri.ieum.main.meeting.dto.MeetingParticipantItem;
import shinhan.fibri.ieum.main.meeting.dto.MeetingParticipantsResponse;
import shinhan.fibri.ieum.main.meeting.exception.HostCannotLeaveException;
import shinhan.fibri.ieum.main.meeting.exception.InvalidMeetingRequestException;
import shinhan.fibri.ieum.main.meeting.exception.KickedMemberException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingFullException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingNotFoundException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingNotOpenException;
import shinhan.fibri.ieum.main.meeting.exception.NotHostException;
import shinhan.fibri.ieum.main.meeting.exception.ParticipantNotFoundException;
import shinhan.fibri.ieum.main.meeting.exception.ScheduleAlreadyExistsException;
import shinhan.fibri.ieum.main.meeting.exception.ScheduleNotCancellableException;
import shinhan.fibri.ieum.main.meeting.exception.ScheduleNotFoundException;
import shinhan.fibri.ieum.main.meeting.repository.MeetingDetailProjection;
import shinhan.fibri.ieum.main.meeting.repository.MeetingParticipantRepository;
import shinhan.fibri.ieum.main.meeting.repository.MeetingRecurrenceRuleRepository;
import shinhan.fibri.ieum.main.meeting.repository.MeetingRepository;
import shinhan.fibri.ieum.main.meeting.repository.MeetingScheduleRepository;
import shinhan.fibri.ieum.main.pin.domain.PinType;
import shinhan.fibri.ieum.main.pin.repository.PinWriter;

@Service
@RequiredArgsConstructor
public class MeetingService {

	private static final ZoneId RESPONSE_ZONE = ZoneId.of("Asia/Seoul");
	private static final int INITIAL_RECURRING_SCHEDULE_LIMIT = 12;

	private final MeetingRepository meetingRepository;
	private final MeetingScheduleRepository meetingScheduleRepository;
	private final MeetingRecurrenceRuleRepository recurrenceRuleRepository;
	private final MeetingParticipantRepository participantRepository;
	private final FileRepository fileRepository;
	private final PinWriter pinWriter;
	private final ChatRoomLifecycle chatRoomLifecycle;

	@Transactional
	public CreateMeetingResponse create(AuthenticatedUser principal, CreateMeetingRequest request) {
		validateCreateRuleCombination(request);
		UUID imageFileId = validateImage(request.imageFileId(), principal.userId());
		Long pinId = pinWriter.create(principal.userId(), PinType.meeting, request.lat(), request.lng());
		Meeting meeting = meetingRepository.save(Meeting.create(
			pinId,
			principal.userId(),
			request.type(),
			request.title(),
			request.content(),
			request.placeName(),
			request.schedule().startsAt(),
			request.maxMembers(),
			imageFileId,
			imageFileId
		));
		List<MeetingSchedule> schedules = createInitialSchedules(meeting.getId(), request);
		MeetingSchedule firstSchedule = null;
		for (MeetingSchedule schedule : schedules) {
			MeetingSchedule saved = meetingScheduleRepository.save(schedule);
			if (firstSchedule == null) {
				firstSchedule = saved;
			}
		}
		if (request.type() == MeetingType.recurring) {
			recurrenceRuleRepository.save(createRecurrenceRule(meeting.getId(), request.recurrenceRule()));
		}
		participantRepository.save(MeetingParticipant.join(meeting.getId(), principal.userId(), OffsetDateTime.now()));
		Long roomId = chatRoomLifecycle.createGroupRoom(meeting.getId(), principal.userId());
		return new CreateMeetingResponse(meeting.getId(), pinId, roomId, firstSchedule.getId());
	}

	@Transactional(readOnly = true)
	public MeetingDetailResponse getDetail(AuthenticatedUser principal, Long meetingId) {
		MeetingDetailProjection detail = meetingRepository.findDetailById(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		Optional<MeetingParticipant> participant = participantRepository.findByIdMeetingIdAndIdUserId(
			meetingId,
			principal.userId()
		);
		ensureNotKicked(participant);
		long participantCount = participantRepository.countByIdMeetingIdAndStatus(
			meetingId,
			shinhan.fibri.ieum.main.meeting.domain.ParticipantStatus.joined
		);
		return new MeetingDetailResponse(
			detail.getMeetingId(),
			detail.getPinId(),
			detail.getRoomId(),
			detail.getTitle(),
			detail.getContent(),
			detail.getPlaceName(),
			detail.getMeetingAt().atZone(RESPONSE_ZONE).toOffsetDateTime(),
			detail.getStatus(),
			detail.getMaxMembers(),
			participantCount,
			new MeetingHostSummary(
				detail.getHostUserId(),
				detail.getHostNickname(),
				fileUrl(detail.getHostProfileFileId(), null)
			),
			fileUrl(detail.getImageFileId(), "display"),
			fileUrl(detail.getThumbnailFileId(), "thumb"),
			new MeetingLocation(detail.getLatitude(), detail.getLongitude()),
			myStatus(principal.userId(), detail, participant),
			detail.getCreatedAt().atZone(RESPONSE_ZONE).toOffsetDateTime()
		);
	}

	@Transactional(readOnly = true)
	public MeetingParticipantsResponse getParticipants(AuthenticatedUser principal, Long meetingId) {
		Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		ensureNotKicked(participantRepository.findByIdMeetingIdAndIdUserId(meetingId, principal.userId()));
		List<MeetingParticipantItem> items = participantRepository.findJoinedParticipantsByMeetingId(meetingId)
			.stream()
			.map(row -> new MeetingParticipantItem(
				row.getUserId(),
				row.getNickname(),
				fileUrl(row.getProfileFileId(), null),
				meeting.getHostId().equals(row.getUserId()),
				row.getJoinedAt().atZone(RESPONSE_ZONE).toOffsetDateTime()
			))
			.toList();
		return new MeetingParticipantsResponse(items);
	}

	@Transactional
	public JoinMeetingResponse join(AuthenticatedUser principal, Long meetingId) {
		Meeting meeting = meetingRepository.findActiveByIdForUpdate(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		OffsetDateTime now = OffsetDateTime.now();
		Optional<MeetingParticipant> participant = participantRepository.findByIdMeetingIdAndIdUserId(
			meetingId,
			principal.userId()
		);
		if (participant.map(row -> row.getStatus() == ParticipantStatus.kicked).orElse(false)) {
			throw new KickedMemberException();
		}
		if (meeting.getStatus() != MeetingStatus.open || !meetingScheduleRepository.existsActiveSchedule(meetingId, now)) {
			throw new MeetingNotOpenException();
		}
		Long roomId = meetingRepository.findGroupRoomIdByMeetingId(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		return participant
			.map(row -> joinExistingParticipant(meeting, row, roomId, principal.userId()))
			.orElseGet(() -> joinNewParticipant(meeting, roomId, principal.userId()));
	}

	private JoinMeetingResponse joinExistingParticipant(
		Meeting meeting,
		MeetingParticipant participant,
		Long roomId,
		Long userId
	) {
		if (participant.getStatus() == ParticipantStatus.kicked) {
			throw new KickedMemberException();
		}
		if (participant.getStatus() == ParticipantStatus.joined) {
			return new JoinMeetingResponse(roomId);
		}
		ensureHasCapacity(meeting);
		participant.rejoin(OffsetDateTime.now());
		chatRoomLifecycle.addMember(roomId, userId);
		return new JoinMeetingResponse(roomId);
	}

	private JoinMeetingResponse joinNewParticipant(Meeting meeting, Long roomId, Long userId) {
		ensureHasCapacity(meeting);
		participantRepository.save(MeetingParticipant.join(meeting.getId(), userId, OffsetDateTime.now()));
		chatRoomLifecycle.addMember(roomId, userId);
		return new JoinMeetingResponse(roomId);
	}

	@Transactional
	public CreateMeetingScheduleResponse addSchedule(
		AuthenticatedUser principal,
		Long meetingId,
		CreateMeetingScheduleRequest request
	) {
		Meeting meeting = meetingRepository.findActiveByIdForUpdate(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		ensureScheduleManager(principal, meeting);
		if (meeting.getType() == MeetingType.recurring) {
			throw new InvalidMeetingRequestException(
				"VALIDATION_FAILED",
				"type",
				"recurring schedule is managed by recurrenceRule"
			);
		}
		OffsetDateTime now = OffsetDateTime.now();
		if (meetingScheduleRepository.existsActiveSchedule(meetingId, now)) {
			throw new ScheduleAlreadyExistsException();
		}
		MeetingSchedule schedule = meetingScheduleRepository.save(createSchedule(
			meetingId,
			request.startsAt(),
			request.endsAt(),
			meetingScheduleRepository.findMaxSequenceNoByMeetingId(meetingId) + 1
		));
		meeting.updateMeetingAtCache(schedule.getStartsAt());
		return new CreateMeetingScheduleResponse(schedule.getId());
	}

	@Transactional
	public void cancelSchedule(AuthenticatedUser principal, Long meetingId, Long scheduleId) {
		Meeting meeting = meetingRepository.findActiveByIdForUpdate(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		ensureScheduleManager(principal, meeting);
		MeetingSchedule schedule = meetingScheduleRepository
			.findByIdAndMeetingIdAndDeletedAtIsNull(scheduleId, meetingId)
			.orElseThrow(ScheduleNotFoundException::new);
		try {
			schedule.cancel();
		} catch (IllegalStateException exception) {
			throw new ScheduleNotCancellableException();
		}
		meetingScheduleRepository.findNextActiveStartsAt(meetingId, OffsetDateTime.now())
			.ifPresent(meeting::updateMeetingAtCache);
	}

	private void ensureScheduleManager(AuthenticatedUser principal, Meeting meeting) {
		if (!meeting.getHostId().equals(principal.userId()) && principal.role() != UserRole.admin) {
			throw new NotHostException();
		}
	}

	private void ensureNotKicked(Optional<MeetingParticipant> participant) {
		if (participant.map(row -> row.getStatus() == ParticipantStatus.kicked).orElse(false)) {
			throw new KickedMemberException();
		}
	}

	private void ensureHasCapacity(Meeting meeting) {
		long joinedCount = participantRepository.countByIdMeetingIdAndStatus(
			meeting.getId(),
			ParticipantStatus.joined
		);
		if (joinedCount >= meeting.getMaxMembers()) {
			throw new MeetingFullException();
		}
	}

	private void validateCreateRuleCombination(CreateMeetingRequest request) {
		if (request.type() == MeetingType.one_time && request.recurrenceRule() != null) {
			throw new InvalidMeetingRequestException(
				"VALIDATION_FAILED",
				"recurrenceRule",
				"recurrenceRule is only allowed for recurring meeting"
			);
		}
		if (request.type() == MeetingType.recurring && request.recurrenceRule() == null) {
			throw new InvalidMeetingRequestException(
				"VALIDATION_FAILED",
				"recurrenceRule",
				"recurrenceRule is required for recurring meeting"
			);
		}
	}

	private List<MeetingSchedule> createInitialSchedules(Long meetingId, CreateMeetingRequest request) {
		if (request.type() == MeetingType.one_time) {
			return List.of(createSchedule(meetingId, request.schedule().startsAt(), request.schedule().endsAt(), 1));
		}
		List<OffsetDateTime> startsAtList = recurringStartsAt(request);
		List<MeetingSchedule> schedules = new ArrayList<>(startsAtList.size());
		Duration duration = request.schedule().endsAt() == null
			? null
			: Duration.between(request.schedule().startsAt(), request.schedule().endsAt());
		for (int index = 0; index < startsAtList.size(); index++) {
			OffsetDateTime startsAt = startsAtList.get(index);
			OffsetDateTime endsAt = duration == null ? null : startsAt.plus(duration);
			schedules.add(createSchedule(meetingId, startsAt, endsAt, index + 1));
		}
		if (schedules.isEmpty()) {
			throw new InvalidMeetingRequestException(
				"VALIDATION_FAILED",
				"recurrenceRule",
				"recurrenceRule does not create schedules"
			);
		}
		return schedules;
	}

	private MeetingSchedule createSchedule(Long meetingId, OffsetDateTime startsAt, OffsetDateTime endsAt, int sequenceNo) {
		return MeetingSchedule.create(
			meetingId,
			startsAt,
			endsAt,
			MeetingScheduleTimePolicy.visibleUntil(startsAt),
			sequenceNo
		);
	}

	private List<OffsetDateTime> recurringStartsAt(CreateMeetingRequest request) {
		CreateMeetingRecurrenceRuleRequest rule = request.recurrenceRule();
		ZoneId zone = ZoneId.of(rule.timezone() == null || rule.timezone().isBlank() ? "Asia/Seoul" : rule.timezone());
		ZonedDateTime firstStart = request.schedule().startsAt().atZoneSameInstant(zone);
		LocalTime time = firstStart.toLocalTime();
		LocalDate firstDate = firstStart.toLocalDate();
		LocalDate current = firstDate.isAfter(rule.startsOn()) ? firstDate : rule.startsOn();
		LocalDate until = rule.endsOn() == null ? rule.startsOn().plusYears(1) : rule.endsOn();
		int limit = rule.maxOccurrences() == null
			? INITIAL_RECURRING_SCHEDULE_LIMIT
			: Math.min(rule.maxOccurrences(), INITIAL_RECURRING_SCHEDULE_LIMIT);
		List<OffsetDateTime> startsAtList = new ArrayList<>(limit);
		while (!current.isAfter(until) && startsAtList.size() < limit) {
			if (matchesRecurrence(current, rule)) {
				OffsetDateTime startsAt = current.atTime(time).atZone(zone).toOffsetDateTime();
				if (!startsAt.isBefore(request.schedule().startsAt())) {
					startsAtList.add(startsAt);
				}
			}
			current = current.plusDays(1);
		}
		return startsAtList;
	}

	private boolean matchesRecurrence(LocalDate date, CreateMeetingRecurrenceRuleRequest rule) {
		return switch (rule.frequency()) {
			case daily -> daysBetween(rule.startsOn(), date) % rule.intervalValue() == 0;
			case weekly -> weeksBetween(rule.startsOn(), date) % rule.intervalValue() == 0
				&& rule.daysOfWeek() != null
				&& rule.daysOfWeek().contains(date.getDayOfWeek().getValue());
			case monthly -> monthsBetween(rule.startsOn(), date) % rule.intervalValue() == 0
				&& rule.dayOfMonth() != null
				&& date.getDayOfMonth() == rule.dayOfMonth();
		};
	}

	private long daysBetween(LocalDate start, LocalDate end) {
		return ChronoUnit.DAYS.between(start, end);
	}

	private long weeksBetween(LocalDate start, LocalDate end) {
		return ChronoUnit.WEEKS.between(start, end);
	}

	private long monthsBetween(LocalDate start, LocalDate end) {
		return ChronoUnit.MONTHS.between(
			YearMonth.from(start).atDay(1),
			YearMonth.from(end).atDay(1)
		);
	}

	private MeetingRecurrenceRule createRecurrenceRule(Long meetingId, CreateMeetingRecurrenceRuleRequest rule) {
		return switch (rule.frequency()) {
			case daily -> MeetingRecurrenceRule.createDaily(
				meetingId,
				rule.intervalValue(),
				rule.startsOn(),
				rule.endsOn(),
				rule.maxOccurrences(),
				rule.timezone()
			);
			case weekly -> MeetingRecurrenceRule.createWeekly(
				meetingId,
				rule.intervalValue(),
				rule.daysOfWeek(),
				rule.startsOn(),
				rule.endsOn(),
				rule.maxOccurrences(),
				rule.timezone()
			);
			case monthly -> MeetingRecurrenceRule.createMonthly(
				meetingId,
				rule.intervalValue(),
				rule.dayOfMonth(),
				rule.startsOn(),
				rule.endsOn(),
				rule.maxOccurrences(),
				rule.timezone()
			);
		};
	}

	@Transactional
	public void leave(AuthenticatedUser principal, Long meetingId) {
		Meeting meeting = meetingRepository.findById(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		if (meeting.getHostId().equals(principal.userId())) {
			throw new HostCannotLeaveException();
		}
		MeetingParticipant participant = participantRepository
			.findByIdMeetingIdAndIdUserId(meetingId, principal.userId())
			.filter(row -> row.getStatus() == ParticipantStatus.joined)
			.orElseThrow(ParticipantNotFoundException::new);
		Long roomId = meetingRepository.findGroupRoomIdByMeetingId(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		participant.leave();
		try {
			chatRoomLifecycle.removeMember(roomId, principal.userId());
		} catch (NotRoomMemberException ignored) {
			// meeting_participants is the source of truth; tolerate older chat-only leave history.
		}
	}

	@Transactional
	public void kick(AuthenticatedUser principal, Long meetingId, KickMeetingRequest request) {
		Meeting meeting = meetingRepository.findById(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		if (!meeting.getHostId().equals(principal.userId())) {
			throw new NotHostException();
		}
		if (meeting.getHostId().equals(request.userId())) {
			throw new InvalidMeetingRequestException("VALIDATION_FAILED", "userId", "Host cannot be kicked");
		}
		MeetingParticipant participant = participantRepository
			.findByIdMeetingIdAndIdUserId(meetingId, request.userId())
			.filter(row -> row.getStatus() == ParticipantStatus.joined)
			.orElseThrow(ParticipantNotFoundException::new);
		Long roomId = meetingRepository.findGroupRoomIdByMeetingId(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		participant.kick();
		try {
			chatRoomLifecycle.removeMember(roomId, request.userId());
		} catch (NotRoomMemberException ignored) {
			// meeting_participants is the source of truth; tolerate older chat-only leave history.
		}
	}

	@Transactional
	public void close(AuthenticatedUser principal, Long meetingId) {
		Meeting meeting = meetingRepository.findById(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		if (!meeting.getHostId().equals(principal.userId())) {
			throw new NotHostException();
		}
		try {
			meeting.close();
		} catch (IllegalStateException exception) {
			throw new MeetingNotOpenException();
		}
	}

	@Transactional
	public void cancel(AuthenticatedUser principal, Long meetingId) {
		Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		if (!meeting.getHostId().equals(principal.userId())) {
			throw new NotHostException();
		}
		OffsetDateTime deletedAt = OffsetDateTime.now();
		meeting.cancel(deletedAt);
		pinWriter.softDelete(meeting.getPinId(), deletedAt);
	}

	private UUID validateImage(UUID imageFileId, Long userId) {
		if (imageFileId == null) {
			return null;
		}
		File file = fileRepository.findByFileIdAndUploaderId(imageFileId, userId)
			.filter(File::isUploaded)
			.filter(this::isImage)
			.orElseThrow(() -> new InvalidMeetingRequestException(
				"VALIDATION_FAILED",
				"imageFileId",
				"Invalid image"
			));
		return file.getFileId();
	}

	private boolean isImage(File file) {
		return file.getContentType() != null && file.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("image/");
	}

	private String myStatus(Long userId, MeetingDetailProjection detail, Optional<MeetingParticipant> participant) {
		if (detail.getHostUserId().equals(userId)) {
			return "host";
		}
		return participant
			.map(row -> row.getStatus().name())
			.orElse("none");
	}

	private String fileUrl(UUID fileId, String variant) {
		if (fileId == null) {
			return null;
		}
		String url = "/api/v1/files/" + fileId;
		return variant == null ? url : url + "?v=" + variant;
	}
}
