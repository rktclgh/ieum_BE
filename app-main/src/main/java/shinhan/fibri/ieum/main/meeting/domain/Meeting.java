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
import java.util.UUID;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

@Entity
@Table(name = "meetings")
public class Meeting {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "meeting_id")
	private Long id;

	@Column(name = "pin_id", nullable = false, unique = true)
	private Long pinId;

	@Column(name = "host_id", nullable = false)
	private Long hostId;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(name = "type", nullable = false, columnDefinition = "meeting_type")
	private MeetingType type;

	@Column(name = "title", nullable = false, length = 200)
	private String title;

	@Column(name = "content")
	private String content;

	@Column(name = "meeting_at")
	private OffsetDateTime meetingAt;

	@Column(name = "max_members", nullable = false)
	private short maxMembers;

	@Column(name = "image_file_id")
	private UUID imageFileId;

	@Column(name = "thumbnail_file_id")
	private UUID thumbnailFileId;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(name = "status", nullable = false, columnDefinition = "meeting_status")
	private MeetingStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	@Column(name = "deleted_at")
	private OffsetDateTime deletedAt;

	protected Meeting() {
	}

	private Meeting(
		Long pinId,
		Long hostId,
		MeetingType type,
		String title,
		String content,
		OffsetDateTime meetingAt,
		int maxMembers,
		UUID imageFileId,
		UUID thumbnailFileId
	) {
		if (maxMembers < 2) {
			throw new IllegalArgumentException("maxMembers must be at least 2");
		}
		if (maxMembers > Short.MAX_VALUE) {
			throw new IllegalArgumentException("maxMembers must not exceed " + Short.MAX_VALUE);
		}
		this.pinId = Objects.requireNonNull(pinId, "pinId must not be null");
		this.hostId = Objects.requireNonNull(hostId, "hostId must not be null");
		this.type = Objects.requireNonNull(type, "type must not be null");
		this.title = Objects.requireNonNull(title, "title must not be null");
		this.content = content;
		this.meetingAt = meetingAt;
		this.maxMembers = (short) maxMembers;
		this.imageFileId = imageFileId;
		this.thumbnailFileId = thumbnailFileId;
		this.status = MeetingStatus.open;
		this.createdAt = OffsetDateTime.now();
		this.updatedAt = this.createdAt;
	}

	public static Meeting create(
		Long pinId,
		Long hostId,
		MeetingType type,
		String title,
		String content,
		OffsetDateTime meetingAt,
		int maxMembers,
		UUID imageFileId,
		UUID thumbnailFileId
	) {
		return new Meeting(
			pinId,
			hostId,
			type,
			title,
			content,
			meetingAt,
			maxMembers,
			imageFileId,
			thumbnailFileId
		);
	}

	public void close() {
		if (status != MeetingStatus.open) {
			throw new IllegalStateException("Meeting is not open");
		}
		this.status = MeetingStatus.closed;
		this.updatedAt = OffsetDateTime.now();
	}

	public void cancel(OffsetDateTime deletedAt) {
		if (status == MeetingStatus.cancelled) {
			throw new IllegalStateException("Meeting is already cancelled");
		}
		this.status = MeetingStatus.cancelled;
		this.deletedAt = Objects.requireNonNull(deletedAt, "deletedAt must not be null");
		this.updatedAt = deletedAt;
	}

	public void updateMeetingAtCache(OffsetDateTime meetingAt) {
		this.meetingAt = Objects.requireNonNull(meetingAt, "meetingAt must not be null");
		this.updatedAt = OffsetDateTime.now();
	}

	public void clearMeetingAtCache() {
		this.meetingAt = null;
		this.updatedAt = OffsetDateTime.now();
	}

	public Long getId() {
		return id;
	}

	public Long getPinId() {
		return pinId;
	}

	public Long getHostId() {
		return hostId;
	}

	public MeetingType getType() {
		return type;
	}

	public String getTitle() {
		return title;
	}

	public String getContent() {
		return content;
	}

	public OffsetDateTime getMeetingAt() {
		return meetingAt;
	}

	public int getMaxMembers() {
		return maxMembers;
	}

	public UUID getImageFileId() {
		return imageFileId;
	}

	public UUID getThumbnailFileId() {
		return thumbnailFileId;
	}

	public MeetingStatus getStatus() {
		return status;
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
