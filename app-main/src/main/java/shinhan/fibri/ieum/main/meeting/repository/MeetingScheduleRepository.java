package shinhan.fibri.ieum.main.meeting.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.main.meeting.domain.MeetingScheduleStatus;
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

	@Modifying(clearAutomatically = true)
	@Query("""
		update MeetingSchedule schedule
		   set schedule.status = shinhan.fibri.ieum.main.meeting.domain.MeetingScheduleStatus.completed,
		       schedule.updatedAt = :now
		 where schedule.status = shinhan.fibri.ieum.main.meeting.domain.MeetingScheduleStatus.scheduled
		   and schedule.visibleUntil < :now
		   and schedule.deletedAt is null
		""")
	int completeExpiredSchedules(@Param("now") OffsetDateTime now);

	Optional<MeetingSchedule> findByIdAndMeetingIdAndDeletedAtIsNull(Long id, Long meetingId);

	Optional<MeetingSchedule> findFirstByMeetingIdAndStatusAndVisibleUntilGreaterThanEqualAndDeletedAtIsNullOrderByStartsAtAscIdAsc(
		Long meetingId,
		MeetingScheduleStatus status,
		OffsetDateTime now
	);

	default Optional<MeetingSchedule> findFirstActiveSchedule(Long meetingId, OffsetDateTime now) {
		return findFirstByMeetingIdAndStatusAndVisibleUntilGreaterThanEqualAndDeletedAtIsNullOrderByStartsAtAscIdAsc(
			meetingId,
			MeetingScheduleStatus.scheduled,
			now
		);
	}

	boolean existsByMeetingIdAndSequenceNo(Long meetingId, int sequenceNo);

	List<MeetingSchedule> findByMeetingIdAndDeletedAtIsNullOrderBySequenceNoAsc(Long meetingId);

	@Query(value = """
		SELECT *
		  FROM meeting_schedules
		 WHERE meeting_id = :meetingId
		   AND deleted_at IS NULL
		   AND starts_at >= :from
		   AND starts_at <= :to
		 ORDER BY starts_at ASC, schedule_id ASC
		 LIMIT :limit
		""", nativeQuery = true)
	List<MeetingSchedule> findSchedulesInRange(
		@Param("meetingId") Long meetingId,
		@Param("from") OffsetDateTime from,
		@Param("to") OffsetDateTime to,
		@Param("limit") int limit
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
		SELECT m.meeting_id                            AS "meetingId",
		       s.schedule_id                           AS "scheduleId",
		       m.title                                 AS "title",
		       ST_Y(p.location::geometry)              AS "latitude",
		       ST_X(p.location::geometry)              AS "longitude",
		       p.address                               AS "address",
		       p.detail_address                        AS "detailAddress",
		       p.label                                 AS "label",
		       s.starts_on                             AS "startsOn",
		       s.start_time                            AS "startTime",
		       s.end_time                              AS "endTime",
		       s.starts_at                             AS "startsAt",
		       s.ends_at                               AS "endsAt",
		       COALESCE(CAST(s.status AS text), 'unscheduled') AS "status",
		       s.created_by                            AS "createdByUserId",
		       cr.room_id                              AS "roomId",
		       (m.host_id = :userId)                   AS "host"
		  FROM meetings m
		  JOIN pins p
		    ON p.pin_id = m.pin_id
		   AND p.deleted_at IS NULL
		  JOIN meeting_participants mp
		    ON mp.meeting_id = m.meeting_id
		   AND mp.user_id = :userId
		   AND mp.status = 'joined'
		  LEFT JOIN meeting_schedules s
		    ON s.meeting_id = m.meeting_id
		   AND s.deleted_at IS NULL
		   AND s.status = 'scheduled'
		   AND s.starts_at >= :from
		   AND s.starts_at <= :to
		  LEFT JOIN chat_rooms cr
		    ON cr.meeting_id = m.meeting_id
		 WHERE m.deleted_at IS NULL
		   AND (
		       s.schedule_id IS NOT NULL
		       OR (m.type = 'one_time' AND NOT EXISTS (
		           SELECT 1
		             FROM meeting_schedules existing
		            WHERE existing.meeting_id = m.meeting_id
		              AND existing.deleted_at IS NULL
		              AND existing.status IN ('scheduled', 'completed')
		       ))
		   )
		 ORDER BY (s.schedule_id IS NOT NULL) ASC,
		          CASE WHEN s.schedule_id IS NULL THEN m.meeting_id END ASC,
		          s.starts_at ASC NULLS LAST,
		          s.schedule_id ASC NULLS LAST
		 LIMIT :limit
		""", nativeQuery = true)
	List<MeetingCalendarProjection> findCalendarItems(
		@Param("userId") Long userId,
		@Param("from") OffsetDateTime from,
		@Param("to") OffsetDateTime to,
		@Param("limit") int limit
	);
}
