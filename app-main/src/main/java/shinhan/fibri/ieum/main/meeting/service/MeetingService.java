package shinhan.fibri.ieum.main.meeting.service;

import java.time.Duration;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
import shinhan.fibri.ieum.main.meeting.domain.MeetingScheduleStatus;
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
import shinhan.fibri.ieum.main.meeting.exception.SchedulePermissionDeniedException;
import shinhan.fibri.ieum.main.meeting.repository.MeetingDetailProjection;
import shinhan.fibri.ieum.main.meeting.repository.MeetingCalendarProjection;
import shinhan.fibri.ieum.main.meeting.repository.MeetingParticipantRepository;
import shinhan.fibri.ieum.main.meeting.repository.MeetingRecurrenceRuleRepository;
import shinhan.fibri.ieum.main.meeting.repository.MeetingRepository;
import shinhan.fibri.ieum.main.meeting.repository.MeetingScheduleRepository;
import shinhan.fibri.ieum.main.pin.domain.PinType;
import shinhan.fibri.ieum.main.pin.dto.LocationSnapshot;
import shinhan.fibri.ieum.main.pin.repository.PinWriter;
import shinhan.fibri.ieum.main.notification.presence.MeetingCreatedEvent;

@Service
@RequiredArgsConstructor
public class MeetingService {

	private static final ZoneId RESPONSE_ZONE = ZoneId.of("Asia/Seoul");
	private static final int INITIAL_RECURRING_SCHEDULE_LIMIT = 12;
	private static final int DEFAULT_SCHEDULE_RANGE_DAYS = 90;
	private static final int MAX_SCHEDULE_RANGE_DAYS = 366;
	private static final int SCHEDULE_QUERY_LIMIT = 1000;

	private final MeetingRepository meetingRepository;
	private final MeetingScheduleRepository meetingScheduleRepository;
	private final MeetingRecurrenceRuleRepository recurrenceRuleRepository;
	private final MeetingParticipantRepository participantRepository;
	private final FileRepository fileRepository;
	private final PinWriter pinWriter;
	private final ChatRoomLifecycle chatRoomLifecycle;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional
	public CreateMeetingResponse create(AuthenticatedUser principal, CreateMeetingRequest request) {
		validateCreateRuleCombination(request);
		validateScheduleWindow(request.schedule(), "schedule.endsAt");
		validateRecurrenceRule(request);
		OffsetDateTime meetingAtCache = initialMeetingAtCache(request);
		UUID imageFileId = validateImage(request.imageFileId(), principal.userId());
		Long pinId = pinWriter.create(principal.userId(), PinType.meeting, request.location());
		Meeting meeting = meetingRepository.save(Meeting.create(
			pinId,
			principal.userId(),
			request.type(),
			request.title(),
			request.content(),
			meetingAtCache,
			request.maxMembers(),
			imageFileId,
			imageFileId
		));
		List<MeetingSchedule> schedules = createInitialSchedules(meeting.getId(), principal.userId(), request);
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
		eventPublisher.publishEvent(new MeetingCreatedEvent(
			meeting.getId(), principal.userId(), meeting.getTitle(), request.location().lat(), request.location().lng()
		));
		return new CreateMeetingResponse(
			meeting.getId(),
			pinId,
			roomId,
			firstSchedule == null ? null : firstSchedule.getId()
		);
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
		OffsetDateTime now = OffsetDateTime.now();
		Optional<MeetingSchedule> nextSchedule = meetingScheduleRepository.findFirstActiveSchedule(meetingId, now);
		MeetingDetailRecurrenceRuleResponse recurrenceRule = recurrenceRuleRepository.findByMeetingId(meetingId)
			.map(this::toRecurrenceRuleResponse)
			.orElse(null);
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
			detail.getMeetingAt() == null ? null : detail.getMeetingAt().atZone(RESPONSE_ZONE).toOffsetDateTime(),
			detail.getType(),
			"open".equals(detail.getStatus()) && nextSchedule.isPresent(),
			nextSchedule.map(schedule -> toScheduleItem(
				schedule,
				canDeleteSchedule(principal, detail.getHostUserId(), participant, schedule)
			)).orElse(null),
			recurrenceRule,
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
			new LocationSnapshot(
				detail.getLatitude(), detail.getLongitude(), detail.getAddress(), detail.getDetailAddress(), detail.getLabel()
			),
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

	@Transactional(readOnly = true)
	public MeetingSchedulesResponse getSchedules(
		AuthenticatedUser principal,
		Long meetingId,
		OffsetDateTime from,
		OffsetDateTime to
	) {
		Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		ensureMeetingMemberOrHost(principal, meeting);
		OffsetDateTime resolvedFrom = resolveRangeFrom(from);
		OffsetDateTime resolvedTo = resolveRangeTo(resolvedFrom, to);
		List<MeetingScheduleItem> items = meetingScheduleRepository
			.findSchedulesInRange(meetingId, resolvedFrom, resolvedTo, SCHEDULE_QUERY_LIMIT)
			.stream()
			.map(schedule -> toScheduleItem(
				schedule,
				isScheduleOperator(principal, meeting.getHostId())
					|| Objects.equals(schedule.getCreatedBy(), principal.userId())
			))
			.toList();
		return new MeetingSchedulesResponse(items);
	}

	@Transactional(readOnly = true)
	public MeetingCalendarResponse getCalendar(AuthenticatedUser principal, OffsetDateTime from, OffsetDateTime to) {
		OffsetDateTime resolvedFrom = resolveRangeFrom(from);
		OffsetDateTime resolvedTo = resolveRangeTo(resolvedFrom, to);
		List<MeetingCalendarItem> items = meetingScheduleRepository.findCalendarItems(
				principal.userId(),
				resolvedFrom,
				resolvedTo,
				SCHEDULE_QUERY_LIMIT
			)
			.stream()
			.map(row -> toCalendarItem(principal, row))
			.toList();
		return new MeetingCalendarResponse(items);
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
		validateScheduleWindow(request, "endsAt");
		Meeting meeting = meetingRepository.findActiveByIdForUpdate(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		ensureJoinedMeetingMember(meetingId, principal.userId());
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
			principal.userId(),
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
		boolean operator = isScheduleOperator(principal, meeting.getHostId());
		if (!operator) {
			ensureJoinedMeetingMember(meetingId, principal.userId());
		}
		MeetingSchedule schedule = meetingScheduleRepository
			.findByIdAndMeetingIdAndDeletedAtIsNull(scheduleId, meetingId)
			.orElseThrow(ScheduleNotFoundException::new);
		if (!operator && !Objects.equals(schedule.getCreatedBy(), principal.userId())) {
			throw new SchedulePermissionDeniedException();
		}
		try {
			schedule.cancel();
		} catch (IllegalStateException exception) {
			throw new ScheduleNotCancellableException();
		}
		meetingScheduleRepository.findNextActiveStartsAt(meetingId, OffsetDateTime.now())
			.ifPresentOrElse(meeting::updateMeetingAtCache, meeting::clearMeetingAtCache);
	}

	private void ensureJoinedMeetingMember(Long meetingId, Long userId) {
		Optional<MeetingParticipant> participant = participantRepository.findByIdMeetingIdAndIdUserId(
			meetingId,
			userId
		);
		ensureNotKicked(participant);
		participant
			.filter(row -> row.getStatus() == ParticipantStatus.joined)
			.orElseThrow(NotMeetingMemberException::new);
	}

	private boolean isScheduleOperator(AuthenticatedUser principal, Long hostId) {
		return hostId.equals(principal.userId()) || principal.role() == UserRole.admin;
	}

	private boolean canDeleteSchedule(
		AuthenticatedUser principal,
		Long hostId,
		Optional<MeetingParticipant> participant,
		MeetingSchedule schedule
	) {
		if (isScheduleOperator(principal, hostId)) {
			return true;
		}
		return participant.map(row -> row.getStatus() == ParticipantStatus.joined).orElse(false)
			&& Objects.equals(schedule.getCreatedBy(), principal.userId());
	}

	private void ensureNotKicked(Optional<MeetingParticipant> participant) {
		if (participant.map(row -> row.getStatus() == ParticipantStatus.kicked).orElse(false)) {
			throw new KickedMemberException();
		}
	}

	private void ensureMeetingMemberOrHost(AuthenticatedUser principal, Meeting meeting) {
		if (meeting.getHostId().equals(principal.userId())) {
			return;
		}
		Optional<MeetingParticipant> participant = participantRepository.findByIdMeetingIdAndIdUserId(
			meeting.getId(),
			principal.userId()
		);
		ensureNotKicked(participant);
		if (participant.map(row -> row.getStatus() == ParticipantStatus.joined).orElse(false)) {
			return;
		}
		throw new NotMeetingMemberException();
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
		if (request.type() == MeetingType.recurring && request.schedule() == null) {
			throw new InvalidMeetingRequestException(
				"VALIDATION_FAILED",
				"schedule",
				"schedule is required for recurring meeting"
			);
		}
	}

	private void validateScheduleWindow(CreateMeetingScheduleRequest schedule, String field) {
		if (schedule == null || schedule.startsAt() == null || schedule.endsAt() == null) {
			return;
		}
		if (!schedule.endsAt().isAfter(schedule.startsAt())) {
			throw new InvalidMeetingRequestException(
				"VALIDATION_FAILED",
				field,
				"endsAt must be after startsAt"
			);
		}
	}

	private void validateRecurrenceRule(CreateMeetingRequest request) {
		if (request.type() != MeetingType.recurring) {
			return;
		}
		try {
			CreateMeetingRecurrenceRuleRequest rule = request.recurrenceRule();
			ZoneId.of(rule.timezone() == null || rule.timezone().isBlank() ? "Asia/Seoul" : rule.timezone());
			createRecurrenceRule(0L, rule);
		} catch (DateTimeException exception) {
			throw new InvalidMeetingRequestException("VALIDATION_FAILED", "recurrenceRule", "Invalid recurrenceRule");
		} catch (IllegalArgumentException exception) {
			throw new InvalidMeetingRequestException("VALIDATION_FAILED", "recurrenceRule", exception.getMessage());
		}
	}

	private List<MeetingSchedule> createInitialSchedules(
		Long meetingId,
		Long createdBy,
		CreateMeetingRequest request
	) {
		if (request.type() == MeetingType.one_time) {
			if (request.schedule() == null) {
				return List.of();
			}
			return List.of(createSchedule(
				meetingId,
				createdBy,
				request.schedule().startsAt(),
				request.schedule().endsAt(),
				1
			));
		}
		List<OffsetDateTime> startsAtList = recurringStartsAt(request);
		List<MeetingSchedule> schedules = new ArrayList<>(startsAtList.size());
		Duration duration = request.schedule().endsAt() == null
			? null
			: Duration.between(request.schedule().startsAt(), request.schedule().endsAt());
		for (int index = 0; index < startsAtList.size(); index++) {
			OffsetDateTime startsAt = startsAtList.get(index);
			OffsetDateTime endsAt = duration == null ? null : startsAt.plus(duration);
			schedules.add(createSchedule(meetingId, createdBy, startsAt, endsAt, index + 1));
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

	private OffsetDateTime initialMeetingAtCache(CreateMeetingRequest request) {
		if (request.type() == MeetingType.one_time) {
			return request.schedule() == null ? null : request.schedule().startsAt();
		}
		List<OffsetDateTime> startsAtList = recurringStartsAt(request);
		if (startsAtList.isEmpty()) {
			throw new InvalidMeetingRequestException(
				"VALIDATION_FAILED",
				"recurrenceRule",
				"recurrenceRule does not create schedules"
			);
		}
		return startsAtList.getFirst();
	}

	private MeetingSchedule createSchedule(
		Long meetingId,
		Long createdBy,
		OffsetDateTime startsAt,
		OffsetDateTime endsAt,
		int sequenceNo
	) {
		return MeetingSchedule.create(
			meetingId,
			createdBy,
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
		Optional<LocalDate> anchorDate = firstActualDate(current, until, rule);
		if (anchorDate.isEmpty()) {
			return List.of();
		}
		current = anchorDate.get();
		List<OffsetDateTime> startsAtList = new ArrayList<>(limit);
		while (!current.isAfter(until) && startsAtList.size() < limit) {
			if (matchesRecurrence(current, rule, anchorDate.get())) {
				OffsetDateTime startsAt = current.atTime(time).atZone(zone).toOffsetDateTime();
				if (!startsAt.isBefore(request.schedule().startsAt())) {
					startsAtList.add(startsAt);
				}
			}
			current = current.plusDays(1);
		}
		return startsAtList;
	}

	private Optional<LocalDate> firstActualDate(
		LocalDate start,
		LocalDate until,
		CreateMeetingRecurrenceRuleRequest rule
	) {
		LocalDate current = start;
		while (!current.isAfter(until)) {
			if (matchesFrequencyDay(current, rule)) {
				return Optional.of(current);
			}
			current = current.plusDays(1);
		}
		return Optional.empty();
	}

	private boolean matchesFrequencyDay(LocalDate date, CreateMeetingRecurrenceRuleRequest rule) {
		return switch (rule.frequency()) {
			case daily -> true;
			case weekly -> rule.daysOfWeek() != null && rule.daysOfWeek().contains(date.getDayOfWeek().getValue());
			case monthly -> rule.dayOfMonth() != null && date.getDayOfMonth() == rule.dayOfMonth();
		};
	}

	private boolean matchesRecurrence(LocalDate date, CreateMeetingRecurrenceRuleRequest rule, LocalDate anchorDate) {
		return switch (rule.frequency()) {
			case daily -> daysBetween(anchorDate, date) % rule.intervalValue() == 0;
			case weekly -> weeksBetween(anchorDate, date) % rule.intervalValue() == 0
				&& rule.daysOfWeek() != null
				&& rule.daysOfWeek().contains(date.getDayOfWeek().getValue());
			case monthly -> monthsBetween(anchorDate, date) % rule.intervalValue() == 0
				&& rule.dayOfMonth() != null
				&& date.getDayOfMonth() == rule.dayOfMonth();
		};
	}

	private long daysBetween(LocalDate start, LocalDate end) {
		return ChronoUnit.DAYS.between(start, end);
	}

	private long weeksBetween(LocalDate start, LocalDate end) {
		return ChronoUnit.WEEKS.between(startOfWeek(start), startOfWeek(end));
	}

	private long monthsBetween(LocalDate start, LocalDate end) {
		return ChronoUnit.MONTHS.between(
			YearMonth.from(start).atDay(1),
			YearMonth.from(end).atDay(1)
		);
	}

	private LocalDate startOfWeek(LocalDate date) {
		return date.minusDays(date.getDayOfWeek().getValue() - 1L);
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

	private OffsetDateTime resolveRangeFrom(OffsetDateTime from) {
		if (from != null) {
			return from;
		}
		return java.time.LocalDate.now(RESPONSE_ZONE)
			.atStartOfDay(RESPONSE_ZONE)
			.toOffsetDateTime();
	}

	private OffsetDateTime resolveRangeTo(OffsetDateTime resolvedFrom, OffsetDateTime to) {
		OffsetDateTime resolvedTo = to == null ? resolvedFrom.plusDays(DEFAULT_SCHEDULE_RANGE_DAYS) : to;
		if (resolvedTo.isBefore(resolvedFrom)) {
			throw new InvalidMeetingRequestException("VALIDATION_FAILED", "from", "must be before to");
		}
		if (Duration.between(resolvedFrom, resolvedTo).toDays() > MAX_SCHEDULE_RANGE_DAYS) {
			throw new InvalidMeetingRequestException("VALIDATION_FAILED", "from", "Range must not exceed 366 days");
		}
		return resolvedTo;
	}

	private MeetingScheduleItem toScheduleItem(MeetingSchedule schedule, boolean canDelete) {
		return new MeetingScheduleItem(
			schedule.getId(),
			toResponseTime(schedule.getStartsAt()),
			toResponseTime(schedule.getEndsAt()),
			schedule.getStatus().name(),
			schedule.getCreatedBy(),
			canDelete && schedule.getStatus() == MeetingScheduleStatus.scheduled
		);
	}

	private MeetingCalendarItem toCalendarItem(AuthenticatedUser principal, MeetingCalendarProjection row) {
		return new MeetingCalendarItem(
			row.getMeetingId(),
			row.getScheduleId(),
			row.getTitle(),
			new LocationSnapshot(
				row.getLatitude(), row.getLongitude(), row.getAddress(), row.getDetailAddress(), row.getLabel()
			),
			toResponseTime(row.getStartsAt()),
			toResponseTime(row.getEndsAt()),
			row.getStatus(),
			row.getCreatedByUserId(),
			!"unscheduled".equals(row.getStatus())
				&& (principal.role() == UserRole.admin
					|| Boolean.TRUE.equals(row.getHost())
					|| Objects.equals(row.getCreatedByUserId(), principal.userId())),
			row.getRoomId(),
			Boolean.TRUE.equals(row.getHost())
		);
	}

	private MeetingDetailRecurrenceRuleResponse toRecurrenceRuleResponse(MeetingRecurrenceRule rule) {
		return new MeetingDetailRecurrenceRuleResponse(
			rule.getFrequency().name(),
			rule.getIntervalValue(),
			toIntegerList(rule.getDaysOfWeek()),
			rule.getDayOfMonth() == null ? null : rule.getDayOfMonth().intValue(),
			rule.getStartsOn(),
			rule.getEndsOn(),
			rule.getMaxOccurrences(),
			rule.getTimezone()
		);
	}

	private List<Integer> toIntegerList(Short[] values) {
		if (values == null) {
			return null;
		}
		return java.util.Arrays.stream(values)
			.map(Short::intValue)
			.toList();
	}

	private OffsetDateTime toResponseTime(OffsetDateTime value) {
		return value == null ? null : value.atZoneSameInstant(RESPONSE_ZONE).toOffsetDateTime();
	}

	private OffsetDateTime toResponseTime(Instant value) {
		return value == null ? null : value.atZone(RESPONSE_ZONE).toOffsetDateTime();
	}
}
