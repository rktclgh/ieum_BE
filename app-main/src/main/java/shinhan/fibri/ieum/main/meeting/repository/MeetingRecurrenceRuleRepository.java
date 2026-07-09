package shinhan.fibri.ieum.main.meeting.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.main.meeting.domain.MeetingRecurrenceRule;

public interface MeetingRecurrenceRuleRepository extends JpaRepository<MeetingRecurrenceRule, Long> {

	@Query("""
		select rule
		from MeetingRecurrenceRule rule, Meeting meeting
		where meeting.id = rule.meetingId
			and meeting.type = shinhan.fibri.ieum.main.meeting.domain.MeetingType.recurring
			and meeting.deletedAt is null
			and (rule.endsOn is null or rule.endsOn >= :today)
			and (
				rule.maxOccurrences is null
				or (
					select count(schedule)
					from MeetingSchedule schedule
					where schedule.meetingId = rule.meetingId
						and schedule.deletedAt is null
				) < rule.maxOccurrences
			)
			and (
				select count(schedule)
				from MeetingSchedule schedule
				where schedule.meetingId = rule.meetingId
					and schedule.status = shinhan.fibri.ieum.main.meeting.domain.MeetingScheduleStatus.scheduled
					and schedule.startsAt >= :now
					and schedule.deletedAt is null
			) < :minimumFutureCount
		""")
	List<MeetingRecurrenceRule> findRulesNeedingExpansion(
		@Param("now") OffsetDateTime now,
		@Param("today") LocalDate today,
		@Param("minimumFutureCount") long minimumFutureCount
	);
}
