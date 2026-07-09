package shinhan.fibri.ieum.main.meeting.dto;

import java.time.OffsetDateTime;

public record MeetingDetailResponse(
	Long meetingId,
	Long pinId,
	Long roomId,
	String title,
	String content,
	String placeName,
	OffsetDateTime meetingAt,
	String status,
	int maxMembers,
	long participantCount,
	MeetingHostSummary host,
	String imageUrl,
	String thumbnailUrl,
	MeetingLocation location,
	String myStatus,
	OffsetDateTime createdAt
) {
}
