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
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "meeting_recurrence_rules")
public class MeetingRecurrenceRule {

	private static final String DEFAULT_TIMEZONE = "Asia/Seoul";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "recurrence_rule_id")
	private Long id;

	@Column(name = "meeting_id", nullable = false, unique = true)
	private Long meetingId;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(name = "frequency", nullable = false, columnDefinition = "recurrence_frequency")
	private RecurrenceFrequency frequency;

	@Column(name = "interval_value", nullable = false)
	private short intervalValue;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "days_of_week", columnDefinition = "smallint[]")
	private Short[] daysOfWeek;

	@Column(name = "day_of_month")
	private Short dayOfMonth;

	@Column(name = "starts_on", nullable = false)
	private LocalDate startsOn;

	@Column(name = "ends_on")
	private LocalDate endsOn;

	@Column(name = "max_occurrences")
	private Integer maxOccurrences;

	@Column(name = "timezone", nullable = false, length = 40)
	private String timezone;

	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	protected MeetingRecurrenceRule() {
	}

	private MeetingRecurrenceRule(
		Long meetingId,
		RecurrenceFrequency frequency,
		int intervalValue,
		List<Integer> daysOfWeek,
		Integer dayOfMonth,
		LocalDate startsOn,
		LocalDate endsOn,
		Integer maxOccurrences,
		String timezone
	) {
		this.meetingId = Objects.requireNonNull(meetingId, "meetingId must not be null");
		this.frequency = Objects.requireNonNull(frequency, "frequency must not be null");
		this.intervalValue = validateInterval(intervalValue);
		this.startsOn = Objects.requireNonNull(startsOn, "startsOn must not be null");
		this.endsOn = endsOn;
		this.maxOccurrences = validateMaxOccurrences(maxOccurrences);
		this.timezone = normalizeTimezone(timezone);
		validateDateRange(startsOn, endsOn);
		this.daysOfWeek = validateDaysOfWeek(frequency, daysOfWeek);
		this.dayOfMonth = validateDayOfMonth(frequency, dayOfMonth);
		this.createdAt = OffsetDateTime.now();
		this.updatedAt = this.createdAt;
	}

	public static MeetingRecurrenceRule createDaily(
		Long meetingId,
		int intervalValue,
		LocalDate startsOn,
		LocalDate endsOn,
		Integer maxOccurrences,
		String timezone
	) {
		return new MeetingRecurrenceRule(
			meetingId,
			RecurrenceFrequency.daily,
			intervalValue,
			null,
			null,
			startsOn,
			endsOn,
			maxOccurrences,
			timezone
		);
	}

	public static MeetingRecurrenceRule createWeekly(
		Long meetingId,
		int intervalValue,
		List<Integer> daysOfWeek,
		LocalDate startsOn,
		LocalDate endsOn,
		Integer maxOccurrences,
		String timezone
	) {
		return new MeetingRecurrenceRule(
			meetingId,
			RecurrenceFrequency.weekly,
			intervalValue,
			daysOfWeek,
			null,
			startsOn,
			endsOn,
			maxOccurrences,
			timezone
		);
	}

	public static MeetingRecurrenceRule createMonthly(
		Long meetingId,
		int intervalValue,
		Integer dayOfMonth,
		LocalDate startsOn,
		LocalDate endsOn,
		Integer maxOccurrences,
		String timezone
	) {
		return new MeetingRecurrenceRule(
			meetingId,
			RecurrenceFrequency.monthly,
			intervalValue,
			null,
			dayOfMonth,
			startsOn,
			endsOn,
			maxOccurrences,
			timezone
		);
	}

	private short validateInterval(int intervalValue) {
		if (intervalValue < 1 || intervalValue > 12) {
			throw new IllegalArgumentException("intervalValue must be between 1 and 12");
		}
		return (short) intervalValue;
	}

	private Integer validateMaxOccurrences(Integer maxOccurrences) {
		if (maxOccurrences != null && (maxOccurrences < 1 || maxOccurrences > 366)) {
			throw new IllegalArgumentException("maxOccurrences must be between 1 and 366");
		}
		return maxOccurrences;
	}

	private void validateDateRange(LocalDate startsOn, LocalDate endsOn) {
		if (endsOn != null && endsOn.isBefore(startsOn)) {
			throw new IllegalArgumentException("endsOn must not be before startsOn");
		}
	}

	private Short[] validateDaysOfWeek(RecurrenceFrequency frequency, List<Integer> daysOfWeek) {
		if (frequency != RecurrenceFrequency.weekly) {
			return null;
		}
		if (daysOfWeek == null || daysOfWeek.isEmpty()) {
			throw new IllegalArgumentException("daysOfWeek is required for weekly recurrence");
		}
		return daysOfWeek.stream()
			.map(day -> {
				if (day == null || day < 1 || day > 7) {
					throw new IllegalArgumentException("daysOfWeek values must be between 1 and 7");
				}
				return day.shortValue();
			})
			.toArray(Short[]::new);
	}

	private Short validateDayOfMonth(RecurrenceFrequency frequency, Integer dayOfMonth) {
		if (frequency != RecurrenceFrequency.monthly) {
			return null;
		}
		if (dayOfMonth == null) {
			throw new IllegalArgumentException("dayOfMonth is required for monthly recurrence");
		}
		if (dayOfMonth < 1 || dayOfMonth > 31) {
			throw new IllegalArgumentException("dayOfMonth must be between 1 and 31");
		}
		return dayOfMonth.shortValue();
	}

	private String normalizeTimezone(String timezone) {
		if (timezone == null || timezone.isBlank()) {
			return DEFAULT_TIMEZONE;
		}
		return timezone;
	}

	public Long getId() {
		return id;
	}

	public Long getMeetingId() {
		return meetingId;
	}

	public RecurrenceFrequency getFrequency() {
		return frequency;
	}

	public int getIntervalValue() {
		return intervalValue;
	}

	public Short[] getDaysOfWeek() {
		return daysOfWeek == null ? null : Arrays.copyOf(daysOfWeek, daysOfWeek.length);
	}

	public Short getDayOfMonth() {
		return dayOfMonth;
	}

	public LocalDate getStartsOn() {
		return startsOn;
	}

	public LocalDate getEndsOn() {
		return endsOn;
	}

	public Integer getMaxOccurrences() {
		return maxOccurrences;
	}

	public String getTimezone() {
		return timezone;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public OffsetDateTime getUpdatedAt() {
		return updatedAt;
	}
}
