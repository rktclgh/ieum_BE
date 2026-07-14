package shinhan.fibri.ieum.main.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class MeetingScheduleTimePolicyTest {

	@Test
	void visibleUntilUsesKstEndOfStartDate() {
		OffsetDateTime startsAt = OffsetDateTime.parse("2026-07-10T02:00:00Z");

		OffsetDateTime visibleUntil = MeetingScheduleTimePolicy.visibleUntil(startsAt);

		assertThat(visibleUntil).isEqualTo(OffsetDateTime.parse("2026-07-10T23:59:59.999999999+09:00"));
	}

	@Test
	void visibleUntilUsesKstDateAfterTimezoneConversion() {
		OffsetDateTime startsAt = OffsetDateTime.parse("2026-07-10T18:00:00Z");

		OffsetDateTime visibleUntil = MeetingScheduleTimePolicy.visibleUntil(startsAt);

		assertThat(visibleUntil).isEqualTo(OffsetDateTime.parse("2026-07-11T23:59:59.999999999+09:00"));
	}

	@Test
	void visibleUntilIsNotBeforeLateNightStart() {
		OffsetDateTime startsAt = OffsetDateTime.parse("2026-07-10T23:59:59.500+09:00");

		OffsetDateTime visibleUntil = MeetingScheduleTimePolicy.visibleUntil(startsAt);

		assertThat(visibleUntil).isAfter(startsAt);
	}
}
