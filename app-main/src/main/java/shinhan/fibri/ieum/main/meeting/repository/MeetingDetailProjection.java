package shinhan.fibri.ieum.main.meeting.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface MeetingDetailProjection {

	Long getMeetingId();

	Long getPinId();

	Long getRoomId();

	String getTitle();

	String getContent();

	String getPlaceName();

	OffsetDateTime getMeetingAt();

	String getStatus();

	int getMaxMembers();

	Long getHostUserId();

	String getHostNickname();

	UUID getHostProfileFileId();

	UUID getImageFileId();

	UUID getThumbnailFileId();

	double getLatitude();

	double getLongitude();

	OffsetDateTime getCreatedAt();
}
