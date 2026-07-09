package shinhan.fibri.ieum.main.meeting.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.main.meeting.domain.MeetingSchedule;

public interface MeetingScheduleRepository extends JpaRepository<MeetingSchedule, Long> {

	@Query("""
		select count(schedule) > 0
		from MeetingSchedule schedule
		where schedule.meetingId = :meetingId
			and schedule.status = shinhan.fibri.ieum.main.meeting.domain.MeetingScheduleStatus.scheduled
			and schedule.visibleUntil >= :now
			and schedule.deletedAt is null
		""")
	boolean existsActiveSchedule(@Param("meetingId") Long meetingId, @Param("now") OffsetDateTime now);

	Optional<MeetingSchedule> findByIdAndMeetingIdAndDeletedAtIsNull(Long id, Long meetingId);

	List<MeetingSchedule> findByMeetingIdAndDeletedAtIsNullAndStartsAtBetweenOrderByStartsAtAscIdAsc(
		Long meetingId,
		OffsetDateTime from,
		OffsetDateTime to
	);

	@Query("""
		select coalesce(max(schedule.sequenceNo), 0)
		from MeetingSchedule schedule
		where schedule.meetingId = :meetingId
		""")
	int findMaxSequenceNoByMeetingId(@Param("meetingId") Long meetingId);

	@Query("""
		select min(schedule.startsAt)
		from MeetingSchedule schedule
		where schedule.meetingId = :meetingId
			and schedule.status = shinhan.fibri.ieum.main.meeting.domain.MeetingScheduleStatus.scheduled
			and schedule.visibleUntil >= :now
			and schedule.deletedAt is null
		""")
	Optional<OffsetDateTime> findNextActiveStartsAt(@Param("meetingId") Long meetingId, @Param("now") OffsetDateTime now);

	@Query(value = """
		SELECT m.meeting_id              AS "meetingId",
		       s.schedule_id             AS "scheduleId",
		       m.title                   AS "title",
		       m.place_name              AS "placeName",
		       s.starts_at               AS "startsAt",
		       s.ends_at                 AS "endsAt",
		       CAST(s.status AS text)    AS "status",
		       cr.room_id                AS "roomId",
		       (m.host_id = :userId)     AS "host"
		  FROM meeting_schedules s
		  JOIN meetings m
		    ON m.meeting_id = s.meeting_id
		   AND m.deleted_at IS NULL
		  JOIN meeting_participants mp
		    ON mp.meeting_id = m.meeting_id
		   AND mp.user_id = :userId
		   AND mp.status = 'joined'
		  LEFT JOIN chat_rooms cr
		    ON cr.meeting_id = m.meeting_id
		 WHERE s.deleted_at IS NULL
		   AND s.status = 'scheduled'
		   AND s.starts_at >= :from
		   AND s.starts_at <= :to
		 ORDER BY s.starts_at ASC, s.schedule_id ASC
		""", nativeQuery = true)
	List<MeetingCalendarProjection> findCalendarItems(
		@Param("userId") Long userId,
		@Param("from") OffsetDateTime from,
		@Param("to") OffsetDateTime to
	);
}
