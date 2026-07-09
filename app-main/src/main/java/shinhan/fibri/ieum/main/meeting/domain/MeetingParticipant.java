package shinhan.fibri.ieum.main.meeting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

@Entity
@Table(name = "meeting_participants")
public class MeetingParticipant {

	@EmbeddedId
	private MeetingParticipantId id;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(name = "status", nullable = false, columnDefinition = "participant_status")
	private ParticipantStatus status;

	@Column(name = "joined_at", nullable = false)
	private OffsetDateTime joinedAt;

	protected MeetingParticipant() {
	}

	private MeetingParticipant(Long meetingId, Long userId, OffsetDateTime joinedAt) {
		this.id = new MeetingParticipantId(meetingId, userId);
		this.status = ParticipantStatus.joined;
		this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt must not be null");
	}

	public static MeetingParticipant join(Long meetingId, Long userId, OffsetDateTime joinedAt) {
		return new MeetingParticipant(meetingId, userId, joinedAt);
	}

	public void leave() {
		this.status = ParticipantStatus.left;
	}

	public void rejoin(OffsetDateTime joinedAt) {
		if (status == ParticipantStatus.kicked) {
			return;
		}
		this.status = ParticipantStatus.joined;
		this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt must not be null");
	}

	public void kick() {
		this.status = ParticipantStatus.kicked;
	}

	public MeetingParticipantId getId() {
		return id;
	}

	public ParticipantStatus getStatus() {
		return status;
	}

	public OffsetDateTime getJoinedAt() {
		return joinedAt;
	}
}
