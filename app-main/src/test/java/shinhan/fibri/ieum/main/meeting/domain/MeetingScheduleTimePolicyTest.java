package shinhan.fibri.ieum.main.meeting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class MeetingScheduleTimePolicyTest {

	@Test
	void startsAtCombinesDateAndTimeInKst() {
		OffsetDateTime startsAt = MeetingScheduleTimePolicy.startsAt(
			LocalDate.of(2026, 7, 10),
			LocalTime.of(19, 0)
		);

		assertThat(startsAt).isEqualTo(OffsetDateTime.parse("2026-07-10T19:00:00+09:00"));
	}

	@Test
	void startsAtAnchorsTimeUndecidedScheduleToKstMidnight() {
		OffsetDateTime startsAt = MeetingScheduleTimePolicy.startsAt(LocalDate.of(2026, 7, 10), null);

		assertThat(startsAt).isEqualTo(OffsetDateTime.parse("2026-07-10T00:00:00+09:00"));
	}

	@Test
	void endsAtIsNullWhenEndTimeMissing() {
		assertThat(MeetingScheduleTimePolicy.endsAt(LocalDate.of(2026, 7, 10), null)).isNull();
	}

	@Test
	void endsAtCombinesDateAndEndTimeInKst() {
		OffsetDateTime endsAt = MeetingScheduleTimePolicy.endsAt(LocalDate.of(2026, 7, 10), LocalTime.of(21, 30));

		assertThat(endsAt).isEqualTo(OffsetDateTime.parse("2026-07-10T21:30:00+09:00"));
	}

	@Test
	void visibleUntilIsEndOfKstDay() {
		OffsetDateTime visibleUntil = MeetingScheduleTimePolicy.visibleUntil(LocalDate.of(2026, 7, 10));

		assertThat(visibleUntil).isEqualTo(OffsetDateTime.parse("2026-07-10T23:59:59.999999999+09:00"));
	}

	@Test
	void visibleUntilCoversTimeUndecidedScheduleForTheWholeDay() {
		LocalDate date = LocalDate.of(2026, 7, 10);

		assertThat(MeetingScheduleTimePolicy.visibleUntil(date))
			.isAfter(MeetingScheduleTimePolicy.startsAt(date, null))
			.isAfter(MeetingScheduleTimePolicy.startsAt(date, LocalTime.of(23, 59)));
	}

	@Test
	void toKstDateConvertsFromOtherOffsets() {
		assertThat(MeetingScheduleTimePolicy.toKstDate(OffsetDateTime.parse("2026-07-10T18:00:00Z")))
			.isEqualTo(LocalDate.of(2026, 7, 11));
	}

	@Test
	void toKstTimeConvertsFromOtherOffsets() {
		assertThat(MeetingScheduleTimePolicy.toKstTime(OffsetDateTime.parse("2026-07-10T10:00:00Z")))
			.isEqualTo(LocalTime.of(19, 0));
	}
}
