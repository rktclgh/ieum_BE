package shinhan.fibri.ieum.main.meeting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

@Entity
@Table(name = "meeting_schedules")
public class MeetingSchedule {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "schedule_id")
	private Long id;

	@Column(name = "meeting_id", nullable = false)
	private Long meetingId;

	@Column(name = "starts_at", nullable = false)
	private OffsetDateTime startsAt;

	@Column(name = "ends_at")
	private OffsetDateTime endsAt;

	@Column(name = "visible_until", nullable = false)
	private OffsetDateTime visibleUntil;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(name = "status", nullable = false, columnDefinition = "meeting_schedule_status")
	private MeetingScheduleStatus status;

	@Column(name = "sequence_no", nullable = false)
	private int sequenceNo;

	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	@Column(name = "deleted_at")
	private OffsetDateTime deletedAt;

	protected MeetingSchedule() {
	}

	private MeetingSchedule(
		Long meetingId,
		OffsetDateTime startsAt,
		OffsetDateTime endsAt,
		OffsetDateTime visibleUntil,
		int sequenceNo
	) {
		this.meetingId = Objects.requireNonNull(meetingId, "meetingId must not be null");
		this.startsAt = Objects.requireNonNull(startsAt, "startsAt must not be null");
		this.endsAt = endsAt;
		this.visibleUntil = Objects.requireNonNull(visibleUntil, "visibleUntil must not be null");
		if (endsAt != null && !endsAt.isAfter(startsAt)) {
			throw new IllegalArgumentException("endsAt must be after startsAt");
		}
		if (visibleUntil.isBefore(startsAt)) {
			throw new IllegalArgumentException("visibleUntil must not be before startsAt");
		}
		if (sequenceNo < 1) {
			throw new IllegalArgumentException("sequenceNo must be positive");
		}
		this.sequenceNo = sequenceNo;
		this.status = MeetingScheduleStatus.scheduled;
		this.createdAt = OffsetDateTime.now();
		this.updatedAt = this.createdAt;
	}

	public static MeetingSchedule create(
		Long meetingId,
		OffsetDateTime startsAt,
		OffsetDateTime endsAt,
		OffsetDateTime visibleUntil,
		int sequenceNo
	) {
		return new MeetingSchedule(meetingId, startsAt, endsAt, visibleUntil, sequenceNo);
	}

	public void complete() {
		ensureScheduled();
		this.status = MeetingScheduleStatus.completed;
		this.updatedAt = OffsetDateTime.now();
	}

	public void cancel() {
		ensureScheduled();
		this.status = MeetingScheduleStatus.cancelled;
		this.updatedAt = OffsetDateTime.now();
	}

	public boolean visibleAt(OffsetDateTime now) {
		return deletedAt == null
			&& status == MeetingScheduleStatus.scheduled
			&& !visibleUntil.isBefore(Objects.requireNonNull(now, "now must not be null"));
	}

	private void ensureScheduled() {
		if (status != MeetingScheduleStatus.scheduled) {
			throw new IllegalStateException("Meeting schedule is not scheduled");
		}
	}

	public Long getId() {
		return id;
	}

	public Long getMeetingId() {
		return meetingId;
	}

	public OffsetDateTime getStartsAt() {
		return startsAt;
	}

	public OffsetDateTime getEndsAt() {
		return endsAt;
	}

	public OffsetDateTime getVisibleUntil() {
		return visibleUntil;
	}

	public MeetingScheduleStatus getStatus() {
		return status;
	}

	public int getSequenceNo() {
		return sequenceNo;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public OffsetDateTime getUpdatedAt() {
		return updatedAt;
	}

	public OffsetDateTime getDeletedAt() {
		return deletedAt;
	}
}
