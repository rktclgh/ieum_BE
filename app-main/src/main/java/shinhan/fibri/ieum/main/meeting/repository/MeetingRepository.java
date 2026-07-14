package shinhan.fibri.ieum.main.meeting.repository;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.main.meeting.domain.Meeting;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

	Optional<Meeting> findByIdAndDeletedAtIsNull(Long id);

	boolean existsByIdAndHostIdAndDeletedAtIsNull(Long id, Long hostId);

	@Query(value = """
		SELECT m.meeting_id                         AS "meetingId",
		       m.pin_id                             AS "pinId",
		       cr.room_id                           AS "roomId",
		       m.title                              AS "title",
		       m.content                            AS "content",
		       m.meeting_at                         AS "meetingAt",
		       CAST(m.type AS text)                 AS "type",
		       CAST(m.status AS text)               AS "status",
		       m.max_members                        AS "maxMembers",
		       u.user_id                            AS "hostUserId",
		       u.nickname                           AS "hostNickname",
		       u.profile_file_id                    AS "hostProfileFileId",
		       m.image_file_id                      AS "imageFileId",
		       m.thumbnail_file_id                  AS "thumbnailFileId",
		       ST_Y(p.location::geometry)           AS "latitude",
		       ST_X(p.location::geometry)           AS "longitude",
		       p.address                            AS "address",
		       p.detail_address                     AS "detailAddress",
		       p.label                              AS "label",
		       m.created_at                         AS "createdAt"
		  FROM meetings m
		  JOIN users u ON u.user_id = m.host_id AND u.deleted_at IS NULL
		  JOIN pins p ON p.pin_id = m.pin_id AND p.deleted_at IS NULL
		  LEFT JOIN chat_rooms cr ON cr.meeting_id = m.meeting_id
		 WHERE m.meeting_id = :id
		   AND m.deleted_at IS NULL
		""", nativeQuery = true)
	Optional<MeetingDetailProjection> findDetailById(@Param("id") Long id);

	@Query(value = """
		SELECT cr.room_id
		  FROM chat_rooms cr
		 WHERE cr.meeting_id = :meetingId
		""", nativeQuery = true)
	Optional<Long> findGroupRoomIdByMeetingId(@Param("meetingId") Long meetingId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT m FROM Meeting m WHERE m.id = :id AND m.deletedAt IS NULL")
	Optional<Meeting> findActiveByIdForUpdate(@Param("id") Long id);

}
