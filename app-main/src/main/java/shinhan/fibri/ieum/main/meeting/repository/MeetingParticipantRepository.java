package shinhan.fibri.ieum.main.meeting.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import shinhan.fibri.ieum.main.meeting.domain.MeetingParticipant;
import shinhan.fibri.ieum.main.meeting.domain.MeetingParticipantId;
import shinhan.fibri.ieum.main.meeting.domain.ParticipantStatus;

public interface MeetingParticipantRepository extends JpaRepository<MeetingParticipant, MeetingParticipantId> {

	Optional<MeetingParticipant> findByIdMeetingIdAndIdUserId(Long meetingId, Long userId);

	long countByIdMeetingIdAndStatus(Long meetingId, ParticipantStatus status);

	List<MeetingParticipant> findByIdMeetingIdAndStatusOrderByJoinedAtAsc(
		Long meetingId,
		ParticipantStatus status
	);
}
