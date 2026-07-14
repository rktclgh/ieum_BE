package shinhan.fibri.ieum.main.meeting.dto;

import java.time.OffsetDateTime;

public record MeetingParticipantItem(
	Long userId,
	String nickname,
	String profileImageUrl,
	boolean isHost,
	OffsetDateTime joinedAt
) {
}
