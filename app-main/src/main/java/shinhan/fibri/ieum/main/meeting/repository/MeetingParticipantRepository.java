package shinhan.fibri.ieum.main.meeting.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.main.meeting.domain.MeetingParticipant;
import shinhan.fibri.ieum.main.meeting.domain.MeetingParticipantId;
import shinhan.fibri.ieum.main.meeting.domain.ParticipantStatus;

public interface MeetingParticipantRepository extends JpaRepository<MeetingParticipant, MeetingParticipantId> {

	Optional<MeetingParticipant> findByIdMeetingIdAndIdUserId(Long meetingId, Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		SELECT participant
		FROM MeetingParticipant participant
		WHERE participant.id.meetingId = :meetingId
		  AND participant.id.userId = :userId
		""")
	Optional<MeetingParticipant> findByIdMeetingIdAndIdUserIdForUpdate(
		@Param("meetingId") Long meetingId,
		@Param("userId") Long userId
	);

	long countByIdMeetingIdAndStatus(Long meetingId, ParticipantStatus status);

	List<MeetingParticipant> findByIdMeetingIdAndStatusOrderByJoinedAtAsc(
		Long meetingId,
		ParticipantStatus status
	);

	@Query(value = """
		SELECT u.user_id         AS "userId",
		       u.nickname        AS "nickname",
		       u.profile_file_id AS "profileFileId",
		       mp.joined_at      AS "joinedAt"
		  FROM meeting_participants mp
		  JOIN users u ON u.user_id = mp.user_id AND u.deleted_at IS NULL
		 WHERE mp.meeting_id = :meetingId
		   AND mp.status = 'joined'
		 ORDER BY mp.joined_at ASC
		""", nativeQuery = true)
	List<MeetingParticipantProjection> findJoinedParticipantsByMeetingId(@Param("meetingId") Long meetingId);
}
