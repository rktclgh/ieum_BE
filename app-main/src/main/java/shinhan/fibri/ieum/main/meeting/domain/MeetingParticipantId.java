package shinhan.fibri.ieum.main.meeting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class MeetingParticipantId implements Serializable {

	@Column(name = "meeting_id")
	private Long meetingId;

	@Column(name = "user_id")
	private Long userId;

	protected MeetingParticipantId() {
	}

	public MeetingParticipantId(Long meetingId, Long userId) {
		this.meetingId = Objects.requireNonNull(meetingId, "meetingId must not be null");
		this.userId = Objects.requireNonNull(userId, "userId must not be null");
	}

	public Long meetingId() {
		return meetingId;
	}

	public Long userId() {
		return userId;
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof MeetingParticipantId that)) {
			return false;
		}
		return Objects.equals(meetingId, that.meetingId) && Objects.equals(userId, that.userId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(meetingId, userId);
	}
}
