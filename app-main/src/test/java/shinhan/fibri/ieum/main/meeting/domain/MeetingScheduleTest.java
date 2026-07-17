package shinhan.fibri.ieum.main.meeting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class MeetingScheduleTest {

	@Test
	void createInitializesScheduledMeetingSchedule() {
		OffsetDateTime startsAt = OffsetDateTime.parse("2026-07-10T19:00:00+09:00");
		OffsetDateTime endsAt = OffsetDateTime.parse("2026-07-10T21:00:00+09:00");
		OffsetDateTime visibleUntil = OffsetDateTime.parse("2026-07-10T23:59:59+09:00");

		MeetingSchedule schedule = MeetingSchedule.create(3L, 42L, startsAt, endsAt, visibleUntil, 1);

		assertThat(schedule.getMeetingId()).isEqualTo(3L);
		assertThat(schedule.getCreatedBy()).isEqualTo(42L);
		assertThat(schedule.getStartsAt()).isEqualTo(startsAt);
		assertThat(schedule.getEndsAt()).isEqualTo(endsAt);
		assertThat(schedule.getVisibleUntil()).isEqualTo(visibleUntil);
		assertThat(schedule.getStatus()).isEqualTo(MeetingScheduleStatus.scheduled);
		assertThat(schedule.getSequenceNo()).isEqualTo(1);
		assertThat(schedule.visibleAt(OffsetDateTime.parse("2026-07-10T23:59:58+09:00"))).isTrue();
		assertThat(schedule.visibleAt(OffsetDateTime.parse("2026-07-11T00:00:00+09:00"))).isFalse();
	}

	@Test
	void createRejectsInvalidTimeRange() {
		OffsetDateTime startsAt = OffsetDateTime.parse("2026-07-10T19:00:00+09:00");

		assertThatThrownBy(() -> MeetingSchedule.create(
			3L,
			42L,
			startsAt,
			startsAt,
			OffsetDateTime.parse("2026-07-10T23:59:59+09:00"),
			1
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("endsAt must be after startsAt");
	}

	@Test
	void cancelAndCompleteOnlyAllowScheduledState() {
		MeetingSchedule schedule = schedule();

		schedule.cancel();

		assertThat(schedule.getStatus()).isEqualTo(MeetingScheduleStatus.cancelled);
		assertThat(schedule.visibleAt(OffsetDateTime.parse("2026-07-10T20:00:00+09:00"))).isFalse();
		assertThatThrownBy(schedule::complete).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void managedScheduleKeepsDisplayDetailsAndUpdatesOnlyWhileScheduled() {
		OffsetDateTime startsAt = OffsetDateTime.parse("2099-07-10T19:00:00+09:00");
		OffsetDateTime endsAt = OffsetDateTime.parse("2099-07-10T21:00:00+09:00");
		MeetingSchedule schedule = MeetingSchedule.createManaged(
			3L,
			42L,
			"용산 와인바에서 봅시다",
			"용산역 1번 출구",
			startsAt,
			endsAt,
			OffsetDateTime.parse("2099-07-10T23:59:59+09:00"),
			2
		);

		schedule.update(
			"한강 공원에서 봅시다",
			"여의나루역 2번 출구",
			OffsetDateTime.parse("2099-07-11T19:00:00+09:00"),
			null,
			OffsetDateTime.parse("2099-07-11T23:59:59+09:00")
		);

		assertThat(schedule.getTitle()).isEqualTo("한강 공원에서 봅시다");
		assertThat(schedule.getLocationName()).isEqualTo("여의나루역 2번 출구");
		assertThat(schedule.getStartsAt()).isEqualTo(OffsetDateTime.parse("2099-07-11T19:00:00+09:00"));
		assertThat(schedule.getEndsAt()).isNull();
		assertThat(schedule.getSequenceNo()).isEqualTo(2);
		assertThat(schedule.getCreatedBy()).isEqualTo(42L);

		schedule.cancel();

		assertThatThrownBy(() -> schedule.update(
			"수정 불가",
			"수정 불가",
			OffsetDateTime.parse("2099-07-12T19:00:00+09:00"),
			null,
			OffsetDateTime.parse("2099-07-12T23:59:59+09:00")
		)).isInstanceOf(IllegalStateException.class);
	}

	private MeetingSchedule schedule() {
		return MeetingSchedule.create(
			3L,
			42L,
			OffsetDateTime.parse("2026-07-10T19:00:00+09:00"),
			null,
			OffsetDateTime.parse("2026-07-10T23:59:59+09:00"),
			1
		);
	}
}
