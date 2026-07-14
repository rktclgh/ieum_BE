package shinhan.fibri.ieum.main.meeting.repository;

import java.time.Instant;
import java.util.UUID;

public interface MeetingParticipantProjection {

	Long getUserId();

	String getNickname();

	UUID getProfileFileId();

	Instant getJoinedAt();
}
