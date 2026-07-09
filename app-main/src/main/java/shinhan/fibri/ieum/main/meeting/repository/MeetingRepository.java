package shinhan.fibri.ieum.main.meeting.repository;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.main.meeting.domain.Meeting;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

	Optional<Meeting> findByIdAndDeletedAtIsNull(Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT m FROM Meeting m WHERE m.id = :id AND m.deletedAt IS NULL")
	Optional<Meeting> findActiveByIdForUpdate(@Param("id") Long id);

	@Modifying(clearAutomatically = true)
	@Query("""
		UPDATE Meeting m
		   SET m.status = shinhan.fibri.ieum.main.meeting.domain.MeetingStatus.closed,
		       m.updatedAt = :now
		 WHERE m.status = shinhan.fibri.ieum.main.meeting.domain.MeetingStatus.open
		   AND m.meetingAt < :now
		   AND m.deletedAt IS NULL
		""")
	int closeExpiredOpenMeetings(@Param("now") OffsetDateTime now);
}
