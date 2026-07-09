package shinhan.fibri.ieum.main.meeting.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.main.meeting.domain.MeetingRecurrenceRule;
import shinhan.fibri.ieum.main.meeting.domain.MeetingSchedule;
import shinhan.fibri.ieum.main.meeting.domain.MeetingScheduleStatus;
import shinhan.fibri.ieum.main.meeting.repository.MeetingRecurrenceRuleRepository;
import shinhan.fibri.ieum.main.meeting.repository.MeetingScheduleRepository;

@Service
@RequiredArgsConstructor
public class MeetingScheduleMaintenanceService {

	private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");
	private static final int MINIMUM_FUTURE_RECURRING_SCHEDULES = 4;
	private static final int EXPANSION_SEARCH_LIMIT_DAYS = 366;

	private final MeetingScheduleRepository meetingScheduleRepository;
	private final MeetingRecurrenceRuleRepository meetingRecurrenceRuleRepository;

	@Transactional
	public int completeExpiredSchedules(OffsetDateTime now) {
		return meetingScheduleRepository.completeExpiredSchedules(now);
	}

	@Transactional
	public int expandRecurringSchedules(OffsetDateTime now) {
		int created = 0;
		for (MeetingRecurrenceRule rule : meetingRecurrenceRuleRepository.findRulesNeedingExpansion(
			now,
			now.atZoneSameInstant(DEFAULT_ZONE).toLocalDate(),
			MINIMUM_FUTURE_RECURRING_SCHEDULES
		)) {
			created += expandRule(rule, now);
		}
		return created;
	}

	private int expandRule(MeetingRecurrenceRule rule, OffsetDateTime now) {
		List<MeetingSchedule> schedules = meetingScheduleRepository
			.findByMeetingIdAndDeletedAtIsNullOrderBySequenceNoAsc(rule.getMeetingId());
		if (schedules.isEmpty() || maxOccurrencesReached(rule, schedules.size())) {
			return 0;
		}
		MeetingSchedule last = schedules.getLast();
		ZoneId zone = zone(rule);
		LocalDate anchorDate = schedules.getFirst().getStartsAt().atZoneSameInstant(zone).toLocalDate();
		ZonedDateTime lastStart = last.getStartsAt().atZoneSameInstant(zone);
		LocalTime meetingTime = lastStart.toLocalTime();
		Duration duration = last.getEndsAt() == null ? null : Duration.between(last.getStartsAt(), last.getEndsAt());
		LocalDate current = laterDate(
			lastStart.toLocalDate().plusDays(1),
			now.atZoneSameInstant(zone).toLocalDate()
		);
		int futureCount = futureScheduledCount(schedules, now);
		int totalCount = schedules.size();
		int sequenceNo = last.getSequenceNo() + 1;
		int created = 0;
		for (int searchedDays = 0;
			 searchedDays < EXPANSION_SEARCH_LIMIT_DAYS && futureCount < MINIMUM_FUTURE_RECURRING_SCHEDULES;
			 searchedDays++
		) {
			if (rule.getEndsOn() != null && current.isAfter(rule.getEndsOn())) {
				break;
			}
			if (maxOccurrencesReached(rule, totalCount)) {
				break;
			}
			if (matchesRecurrence(current, rule, anchorDate)) {
				if (meetingScheduleRepository.existsByMeetingIdAndSequenceNo(rule.getMeetingId(), sequenceNo)) {
					return created;
				}
				OffsetDateTime startsAt = current.atTime(meetingTime).atZone(zone).toOffsetDateTime();
				OffsetDateTime endsAt = duration == null ? null : startsAt.plus(duration);
				meetingScheduleRepository.save(MeetingSchedule.create(
					rule.getMeetingId(),
					startsAt,
					endsAt,
					MeetingScheduleTimePolicy.visibleUntil(startsAt),
					sequenceNo
				));
				created++;
				totalCount++;
				sequenceNo++;
				if (!startsAt.isBefore(now)) {
					futureCount++;
				}
			}
			current = current.plusDays(1);
		}
		return created;
	}

	private int futureScheduledCount(List<MeetingSchedule> schedules, OffsetDateTime now) {
		return (int) schedules.stream()
			.filter(schedule -> schedule.getStatus() == MeetingScheduleStatus.scheduled)
			.filter(schedule -> !schedule.getStartsAt().isBefore(now))
			.count();
	}

	private boolean maxOccurrencesReached(MeetingRecurrenceRule rule, int totalCount) {
		return rule.getMaxOccurrences() != null && totalCount >= rule.getMaxOccurrences();
	}

	private LocalDate laterDate(LocalDate first, LocalDate second) {
		return first.isAfter(second) ? first : second;
	}

	private ZoneId zone(MeetingRecurrenceRule rule) {
		return rule.getTimezone() == null || rule.getTimezone().isBlank()
			? DEFAULT_ZONE
			: ZoneId.of(rule.getTimezone());
	}

	private boolean matchesRecurrence(LocalDate date, MeetingRecurrenceRule rule, LocalDate anchorDate) {
		return switch (rule.getFrequency()) {
			case daily -> daysBetween(anchorDate, date) % rule.getIntervalValue() == 0;
			case weekly -> weeksBetween(anchorDate, date) % rule.getIntervalValue() == 0
				&& containsDayOfWeek(rule.getDaysOfWeek(), date.getDayOfWeek().getValue());
			case monthly -> monthsBetween(anchorDate, date) % rule.getIntervalValue() == 0
				&& rule.getDayOfMonth() != null
				&& date.getDayOfMonth() == rule.getDayOfMonth();
		};
	}

	private boolean containsDayOfWeek(Short[] daysOfWeek, int dayOfWeek) {
		if (daysOfWeek == null) {
			return false;
		}
		for (Short value : daysOfWeek) {
			if (value != null && value == dayOfWeek) {
				return true;
			}
		}
		return false;
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
}
