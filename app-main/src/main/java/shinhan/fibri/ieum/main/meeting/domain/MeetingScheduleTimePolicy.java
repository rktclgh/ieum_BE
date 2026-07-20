package shinhan.fibri.ieum.main.meeting.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 일정의 저장 정본(starts_on/start_time/end_time)에서 파생 캐시(starts_at/ends_at/visible_until)를
 * 계산하는 단일 정본. 모든 일정 쓰기 경로가 이 유틸만 사용한다.
 */
public final class MeetingScheduleTimePolicy {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalTime END_OF_VISIBLE_DAY = LocalTime.MAX;

	/** 시간 미정 일정의 파생 시각 anchor. 그 날짜의 맨 앞으로 정렬된다. */
	public static final LocalTime TIME_UNDECIDED_ANCHOR = LocalTime.MIDNIGHT;

	private MeetingScheduleTimePolicy() {
	}

	public static OffsetDateTime startsAt(LocalDate startsOn, LocalTime startTime) {
		return Objects.requireNonNull(startsOn, "startsOn must not be null")
			.atTime(startTime == null ? TIME_UNDECIDED_ANCHOR : startTime)
			.atZone(KST)
			.toOffsetDateTime();
	}

	public static OffsetDateTime endsAt(LocalDate startsOn, LocalTime endTime) {
		if (endTime == null) {
			return null;
		}
		return Objects.requireNonNull(startsOn, "startsOn must not be null")
			.atTime(endTime)
			.atZone(KST)
			.toOffsetDateTime();
	}

	public static OffsetDateTime visibleUntil(LocalDate startsOn) {
		return Objects.requireNonNull(startsOn, "startsOn must not be null")
			.atTime(END_OF_VISIBLE_DAY)
			.atZone(KST)
			.toOffsetDateTime();
	}

	public static LocalDate toKstDate(OffsetDateTime value) {
		return Objects.requireNonNull(value, "value must not be null")
			.atZoneSameInstant(KST)
			.toLocalDate();
	}

	public static LocalTime toKstTime(OffsetDateTime value) {
		return Objects.requireNonNull(value, "value must not be null")
			.atZoneSameInstant(KST)
			.toLocalTime();
	}

	public static LocalDate today() {
		return LocalDate.now(KST);
	}
}
