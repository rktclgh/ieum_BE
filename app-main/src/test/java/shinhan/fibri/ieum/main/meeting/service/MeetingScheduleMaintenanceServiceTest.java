package shinhan.fibri.ieum.main.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.meeting.domain.MeetingRecurrenceRule;
import shinhan.fibri.ieum.main.meeting.domain.MeetingSchedule;
import shinhan.fibri.ieum.main.meeting.repository.MeetingRecurrenceRuleRepository;
import shinhan.fibri.ieum.main.meeting.repository.MeetingScheduleRepository;

class MeetingScheduleMaintenanceServiceTest {

	private final MeetingScheduleRepository repository = mock(MeetingScheduleRepository.class);
	private final MeetingRecurrenceRuleRepository recurrenceRuleRepository = mock(MeetingRecurrenceRuleRepository.class);
	private final MeetingScheduleMaintenanceService service = new MeetingScheduleMaintenanceService(
		repository,
		recurrenceRuleRepository
	);

	@Test
	void completeExpiredSchedulesDelegatesVisibleUntilBulkUpdate() {
		OffsetDateTime now = OffsetDateTime.parse("2026-07-10T00:00:00+09:00");
		when(repository.completeExpiredSchedules(now)).thenReturn(2);

		int updated = service.completeExpiredSchedules(now);

		assertThat(updated).isEqualTo(2);
		verify(repository).completeExpiredSchedules(now);
	}

	@Test
	void expandRecurringSchedulesCreatesSchedulesUntilFourFutureOccurrences() {
		OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
		MeetingRecurrenceRule rule = MeetingRecurrenceRule.createDaily(
			3L,
			1,
			LocalDate.parse("2026-07-07"),
			null,
			null,
			"Asia/Seoul"
		);
		List<MeetingSchedule> schedules = List.of(
			schedule(3L, "2026-07-10T19:00:00+09:00", 4),
			schedule(3L, "2026-07-11T19:00:00+09:00", 5)
		);
		when(recurrenceRuleRepository.findRulesNeedingExpansion(now, now.toLocalDate(), 4))
			.thenReturn(List.of(rule));
		when(repository.findByMeetingIdAndDeletedAtIsNullOrderBySequenceNoAsc(3L)).thenReturn(schedules);

		int created = service.expandRecurringSchedules(now);

		assertThat(created).isEqualTo(2);
		verify(repository).save(scheduleMatching("2026-07-12T19:00:00+09:00", 6));
		verify(repository).save(scheduleMatching("2026-07-13T19:00:00+09:00", 7));
	}

	@Test
	void expandRecurringSchedulesStopsWhenMaxOccurrencesAlreadyReached() {
		OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
		MeetingRecurrenceRule rule = MeetingRecurrenceRule.createDaily(
			3L,
			1,
			LocalDate.parse("2026-07-07"),
			null,
			2,
			"Asia/Seoul"
		);
		when(recurrenceRuleRepository.findRulesNeedingExpansion(now, now.toLocalDate(), 4))
			.thenReturn(List.of(rule));
		when(repository.findByMeetingIdAndDeletedAtIsNullOrderBySequenceNoAsc(3L)).thenReturn(List.of(
			schedule(3L, "2026-07-10T19:00:00+09:00", 1),
			schedule(3L, "2026-07-11T19:00:00+09:00", 2)
		));

		int created = service.expandRecurringSchedules(now);

		assertThat(created).isZero();
		verify(repository, never()).save(any(MeetingSchedule.class));
	}

	@Test
	void expandRecurringSchedulesSkipsPreexistingSequenceWithoutFlushingDuplicate() {
		OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
		MeetingRecurrenceRule rule = MeetingRecurrenceRule.createDaily(
			3L,
			1,
			LocalDate.parse("2026-07-07"),
			null,
			null,
			"Asia/Seoul"
		);
		when(recurrenceRuleRepository.findRulesNeedingExpansion(now, now.toLocalDate(), 4))
			.thenReturn(List.of(rule));
		when(repository.findByMeetingIdAndDeletedAtIsNullOrderBySequenceNoAsc(3L)).thenReturn(List.of(
			schedule(3L, "2026-07-10T19:00:00+09:00", 4)
		));
		when(repository.existsByMeetingIdAndSequenceNo(3L, 5)).thenReturn(true);

		int created = service.expandRecurringSchedules(now);

		assertThat(created).isZero();
		verify(repository, never()).save(any(MeetingSchedule.class));
		verify(repository, never()).saveAndFlush(any(MeetingSchedule.class));
	}

	@Test
	void expandRecurringMonthlySchedulesAnchorsIntervalAtFirstActualOccurrence() {
		OffsetDateTime now = OffsetDateTime.parse("2026-02-16T09:00:00+09:00");
		MeetingRecurrenceRule rule = MeetingRecurrenceRule.createMonthly(
			3L,
			2,
			15,
			LocalDate.parse("2026-01-20"),
			null,
			null,
			"Asia/Seoul"
		);
		when(recurrenceRuleRepository.findRulesNeedingExpansion(now, now.toLocalDate(), 4))
			.thenReturn(List.of(rule));
		when(repository.findByMeetingIdAndDeletedAtIsNullOrderBySequenceNoAsc(3L)).thenReturn(List.of(
			schedule(3L, "2026-02-15T19:00:00+09:00", 1)
		));

		int created = service.expandRecurringSchedules(now);

		assertThat(created).isEqualTo(4);
		verify(repository).save(scheduleMatching("2026-04-15T19:00:00+09:00", 2));
		verify(repository).save(scheduleMatching("2026-06-15T19:00:00+09:00", 3));
		verify(repository).save(scheduleMatching("2026-08-15T19:00:00+09:00", 4));
		verify(repository).save(scheduleMatching("2026-10-15T19:00:00+09:00", 5));
	}

	@Test
	void expandRecurringWeeklySchedulesUsesCalendarWeekBoundariesForInterval() {
		OffsetDateTime now = OffsetDateTime.parse("2026-07-08T09:00:00+09:00");
		MeetingRecurrenceRule rule = MeetingRecurrenceRule.createWeekly(
			3L,
			2,
			List.of(1, 2),
			LocalDate.parse("2026-07-07"),
			LocalDate.parse("2026-08-31"),
			4,
			"Asia/Seoul"
		);
		when(recurrenceRuleRepository.findRulesNeedingExpansion(now, now.toLocalDate(), 4))
			.thenReturn(List.of(rule));
		when(repository.findByMeetingIdAndDeletedAtIsNullOrderBySequenceNoAsc(3L)).thenReturn(List.of(
			schedule(3L, "2026-07-07T19:00:00+09:00", 1)
		));

		int created = service.expandRecurringSchedules(now);

		assertThat(created).isEqualTo(3);
		verify(repository).save(scheduleMatching("2026-07-20T19:00:00+09:00", 2));
		verify(repository).save(scheduleMatching("2026-07-21T19:00:00+09:00", 3));
		verify(repository).save(scheduleMatching("2026-08-03T19:00:00+09:00", 4));
	}

	private MeetingSchedule schedule(Long meetingId, String startsAt, int sequenceNo) {
		OffsetDateTime start = OffsetDateTime.parse(startsAt);
		return MeetingSchedule.create(
			meetingId,
			42L,
			start,
			start.plusHours(1),
			start.withHour(23).withMinute(59).withSecond(59),
			sequenceNo
		);
	}

	private MeetingSchedule scheduleMatching(String startsAt, int sequenceNo) {
		return org.mockito.ArgumentMatchers.argThat(schedule ->
			schedule.getCreatedBy().equals(42L)
				&& schedule.getStartsAt().isEqual(OffsetDateTime.parse(startsAt))
				&& schedule.getEndsAt().isEqual(OffsetDateTime.parse(startsAt).plusHours(1))
				&& schedule.getVisibleUntil().isEqual(OffsetDateTime.parse(startsAt).withHour(23).withMinute(59).withSecond(59).withNano(999999999))
				&& schedule.getSequenceNo() == sequenceNo
		);
	}
}
