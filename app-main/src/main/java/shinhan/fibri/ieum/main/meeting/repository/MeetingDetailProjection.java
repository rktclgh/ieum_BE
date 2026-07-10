package shinhan.fibri.ieum.main.meeting.repository;

import java.time.Instant;
import java.util.UUID;

public interface MeetingDetailProjection {

	Long getMeetingId();

	Long getPinId();

	Long getRoomId();

	String getTitle();

	String getContent();

	Instant getMeetingAt();

	String getType();

	String getStatus();

	int getMaxMembers();

	Long getHostUserId();

	String getHostNickname();

	UUID getHostProfileFileId();

	UUID getImageFileId();

	UUID getThumbnailFileId();

	double getLatitude();

	double getLongitude();

	String getAddress();

	String getDetailAddress();

	String getLabel();

	Instant getCreatedAt();
}
