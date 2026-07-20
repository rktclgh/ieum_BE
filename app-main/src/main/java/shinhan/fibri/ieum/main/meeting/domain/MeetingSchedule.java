package shinhan.fibri.ieum.main.meeting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

/**
 * 회차 하나. 저장 정본은 {@code startsOn}(날짜) + {@code startTime}/{@code endTime}(시각)이며,
 * {@code startTime == null}이면 <b>시간 미정</b>이다. 날짜 미정은 이 row가 없는 것으로 표현한다.
 *
 * <p>{@code startsAt}/{@code endsAt}/{@code visibleUntil}은 {@link MeetingScheduleTimePolicy}가
 * 정본에서 계산해 저장하는 파생 캐시다. 외부에서 주입받지 않으므로 드리프트가 생길 수 없다.
 */
@Entity
@Table(name = "meeting_schedules")
public class MeetingSchedule {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "schedule_id")
	private Long id;

	@Column(name = "meeting_id", nullable = false)
	private Long meetingId;

	@Column(name = "created_by")
	private Long createdBy;

	@Column(name = "title", length = 100)
	private String title;

	@Column(name = "location_name", length = 200)
	private String locationName;

	@Column(name = "starts_on", nullable = false)
	private LocalDate startsOn;

	@Column(name = "start_time")
	private LocalTime startTime;

	@Column(name = "end_time")
	private LocalTime endTime;

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
		Long createdBy,
		LocalDate startsOn,
		LocalTime startTime,
		LocalTime endTime,
		int sequenceNo
	) {
		this.meetingId = Objects.requireNonNull(meetingId, "meetingId must not be null");
		this.createdBy = createdBy;
		if (sequenceNo < 1) {
			throw new IllegalArgumentException("sequenceNo must be positive");
		}
		this.sequenceNo = sequenceNo;
		this.status = MeetingScheduleStatus.scheduled;
		this.createdAt = OffsetDateTime.now();
		this.updatedAt = this.createdAt;
		applyDateTime(startsOn, startTime, endTime);
	}

	public static MeetingSchedule create(
		Long meetingId,
		Long createdBy,
		LocalDate startsOn,
		LocalTime startTime,
		LocalTime endTime,
		int sequenceNo
	) {
		return new MeetingSchedule(meetingId, createdBy, startsOn, startTime, endTime, sequenceNo);
	}

	public static MeetingSchedule createManaged(
		Long meetingId,
		Long createdBy,
		String title,
		String locationName,
		LocalDate startsOn,
		LocalTime startTime,
		LocalTime endTime,
		int sequenceNo
	) {
		MeetingSchedule schedule = new MeetingSchedule(meetingId, createdBy, startsOn, startTime, endTime, sequenceNo);
		schedule.title = requireText(title, "title");
		schedule.locationName = requireText(locationName, "locationName");
		return schedule;
	}

	public void update(
		String title,
		String locationName,
		LocalDate startsOn,
		LocalTime startTime,
		LocalTime endTime
	) {
		ensureScheduled();
		this.title = requireText(title, "title");
		this.locationName = requireText(locationName, "locationName");
		applyDateTime(startsOn, startTime, endTime);
		this.updatedAt = OffsetDateTime.now();
	}

	/** 정본을 검증해 세팅하고 파생 캐시를 다시 계산한다. */
	private void applyDateTime(LocalDate startsOn, LocalTime startTime, LocalTime endTime) {
		this.startsOn = Objects.requireNonNull(startsOn, "startsOn must not be null");
		if (endTime != null && startTime == null) {
			throw new IllegalArgumentException("endTime requires startTime");
		}
		if (endTime != null && !endTime.isAfter(startTime)) {
			throw new IllegalArgumentException("endTime must be after startTime");
		}
		this.startTime = startTime;
		this.endTime = endTime;
		this.startsAt = MeetingScheduleTimePolicy.startsAt(startsOn, startTime);
		this.endsAt = MeetingScheduleTimePolicy.endsAt(startsOn, endTime);
		this.visibleUntil = MeetingScheduleTimePolicy.visibleUntil(startsOn);
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

	/** 시간 미정 여부. FE는 이 값으로 "시간 미정" 뱃지를 렌더한다. */
	public boolean isTimeUndecided() {
		return startTime == null;
	}

	/**
	 * 수정·취소·신고가 가능한 "아직 지나지 않은" 회차인지.
	 *
	 * <p>시간이 확정된 회차는 시작 시각 이전까지, <b>시간 미정 회차는 그 날이 끝날 때까지</b>
	 * (= {@code visibleUntil}) 유효하다. 시간 미정 회차의 파생 시각은 자정이라
	 * {@code startsAt} 기준으로 판정하면 등록 당일에 바로 수정 불가가 되어버린다.
	 */
	public boolean mutableAt(OffsetDateTime now) {
		Objects.requireNonNull(now, "now must not be null");
		if (status != MeetingScheduleStatus.scheduled) {
			return false;
		}
		return isTimeUndecided() ? !visibleUntil.isBefore(now) : startsAt.isAfter(now);
	}

	private void ensureScheduled() {
		if (status != MeetingScheduleStatus.scheduled) {
			throw new IllegalStateException("Meeting schedule is not scheduled");
		}
	}

	private static String requireText(String value, String fieldName) {
		String text = Objects.requireNonNull(value, fieldName + " must not be null").trim();
		if (text.isEmpty()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return text;
	}

	public Long getId() {
		return id;
	}

	public Long getMeetingId() {
		return meetingId;
	}

	public Long getCreatedBy() {
		return createdBy;
	}

	public String getTitle() {
		return title;
	}

	public String getLocationName() {
		return locationName;
	}

	public LocalDate getStartsOn() {
		return startsOn;
	}

	public LocalTime getStartTime() {
		return startTime;
	}

	public LocalTime getEndTime() {
		return endTime;
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
