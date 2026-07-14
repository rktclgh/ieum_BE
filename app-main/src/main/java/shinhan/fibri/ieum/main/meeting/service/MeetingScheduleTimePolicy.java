package shinhan.fibri.ieum.main.meeting.service;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;

final class MeetingScheduleTimePolicy {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalTime END_OF_VISIBLE_DAY = LocalTime.MAX;

	private MeetingScheduleTimePolicy() {
	}

	static OffsetDateTime visibleUntil(OffsetDateTime startsAt) {
		return Objects.requireNonNull(startsAt, "startsAt must not be null")
			.atZoneSameInstant(KST)
			.toLocalDate()
			.atTime(END_OF_VISIBLE_DAY)
			.atZone(KST)
			.toOffsetDateTime();
	}
}
