package shinhan.fibri.ieum.main.meeting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class MeetingScheduleTest {

	@Test
	void createInitializesScheduledMeetingScheduleAndDerivesCachedInstants() {
		MeetingSchedule schedule = MeetingSchedule.create(
			3L,
			42L,
			LocalDate.parse("2026-07-10"),
			LocalTime.parse("19:00"),
			LocalTime.parse("21:00"),
			1
		);

		assertThat(schedule.getMeetingId()).isEqualTo(3L);
		assertThat(schedule.getCreatedBy()).isEqualTo(42L);
		assertThat(schedule.getStartsOn()).isEqualTo(LocalDate.parse("2026-07-10"));
		assertThat(schedule.getStartTime()).isEqualTo(LocalTime.parse("19:00"));
		assertThat(schedule.getEndTime()).isEqualTo(LocalTime.parse("21:00"));
		assertThat(schedule.isTimeUndecided()).isFalse();
		assertThat(schedule.getStartsAt()).isEqualTo(OffsetDateTime.parse("2026-07-10T19:00:00+09:00"));
		assertThat(schedule.getEndsAt()).isEqualTo(OffsetDateTime.parse("2026-07-10T21:00:00+09:00"));
		assertThat(schedule.getVisibleUntil())
			.isEqualTo(OffsetDateTime.parse("2026-07-10T23:59:59.999999999+09:00"));
		assertThat(schedule.getStatus()).isEqualTo(MeetingScheduleStatus.scheduled);
		assertThat(schedule.getSequenceNo()).isEqualTo(1);
		assertThat(schedule.visibleAt(OffsetDateTime.parse("2026-07-10T23:59:58+09:00"))).isTrue();
		assertThat(schedule.visibleAt(OffsetDateTime.parse("2026-07-11T00:00:00+09:00"))).isFalse();
	}

	@Test
	void createSupportsTimeUndecidedSchedule() {
		MeetingSchedule schedule = MeetingSchedule.create(
			3L,
			42L,
			LocalDate.parse("2026-07-10"),
			null,
			null,
			1
		);

		assertThat(schedule.isTimeUndecided()).isTrue();
		assertThat(schedule.getStartTime()).isNull();
		assertThat(schedule.getEndTime()).isNull();
		assertThat(schedule.getStartsAt()).isEqualTo(OffsetDateTime.parse("2026-07-10T00:00:00+09:00"));
		assertThat(schedule.getEndsAt()).isNull();
		// 시간이 미정이어도 그 날 23:59까지는 노출·참여가 유지된다.
		assertThat(schedule.visibleAt(OffsetDateTime.parse("2026-07-10T23:00:00+09:00"))).isTrue();
	}

	@Test
	void createRejectsEndTimeWithoutStartTime() {
		assertThatThrownBy(() -> MeetingSchedule.create(
			3L,
			42L,
			LocalDate.parse("2026-07-10"),
			null,
			LocalTime.parse("21:00"),
			1
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("endTime requires startTime");
	}

	@Test
	void createRejectsInvalidTimeRange() {
		assertThatThrownBy(() -> MeetingSchedule.create(
			3L,
			42L,
			LocalDate.parse("2026-07-10"),
			LocalTime.parse("19:00"),
			LocalTime.parse("19:00"),
			1
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("endTime must be after startTime");
	}

	@Test
	void mutableAtUsesStartInstantWhenTimeIsDecided() {
		MeetingSchedule schedule = MeetingSchedule.create(
			3L,
			42L,
			LocalDate.parse("2026-07-10"),
			LocalTime.parse("19:00"),
			null,
			1
		);

		assertThat(schedule.mutableAt(OffsetDateTime.parse("2026-07-10T18:59:59+09:00"))).isTrue();
		assertThat(schedule.mutableAt(OffsetDateTime.parse("2026-07-10T19:00:01+09:00"))).isFalse();
	}

	@Test
	void mutableAtCoversWholeDayWhenTimeIsUndecided() {
		MeetingSchedule schedule = MeetingSchedule.create(
			3L,
			42L,
			LocalDate.parse("2026-07-10"),
			null,
			null,
			1
		);

		// 자정 anchor 때문에 startsAt 기준으로 보면 등록 당일 내내 수정 불가가 되어버린다.
		assertThat(schedule.mutableAt(OffsetDateTime.parse("2026-07-10T15:00:00+09:00"))).isTrue();
		assertThat(schedule.mutableAt(OffsetDateTime.parse("2026-07-11T00:00:01+09:00"))).isFalse();
	}

	@Test
	void mutableAtIsFalseOnceCancelled() {
		MeetingSchedule schedule = schedule();

		schedule.cancel();

		assertThat(schedule.mutableAt(OffsetDateTime.parse("2026-07-10T10:00:00+09:00"))).isFalse();
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
		MeetingSchedule schedule = MeetingSchedule.createManaged(
			3L,
			42L,
			"용산 와인바에서 봅시다",
			"용산역 1번 출구",
			LocalDate.parse("2099-07-10"),
			LocalTime.parse("19:00"),
			LocalTime.parse("21:00"),
			2
		);

		schedule.update(
			"한강 공원에서 봅시다",
			"여의나루역 2번 출구",
			LocalDate.parse("2099-07-11"),
			LocalTime.parse("19:00"),
			null
		);

		assertThat(schedule.getTitle()).isEqualTo("한강 공원에서 봅시다");
		assertThat(schedule.getLocationName()).isEqualTo("여의나루역 2번 출구");
		assertThat(schedule.getStartsOn()).isEqualTo(LocalDate.parse("2099-07-11"));
		assertThat(schedule.getStartsAt()).isEqualTo(OffsetDateTime.parse("2099-07-11T19:00:00+09:00"));
		assertThat(schedule.getEndsAt()).isNull();
		assertThat(schedule.getVisibleUntil())
			.isEqualTo(OffsetDateTime.parse("2099-07-11T23:59:59.999999999+09:00"));
		assertThat(schedule.getSequenceNo()).isEqualTo(2);
		assertThat(schedule.getCreatedBy()).isEqualTo(42L);

		schedule.cancel();

		assertThatThrownBy(() -> schedule.update(
			"수정 불가",
			"수정 불가",
			LocalDate.parse("2099-07-12"),
			LocalTime.parse("19:00"),
			null
		)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void updateSwitchesBetweenDecidedAndUndecidedTime() {
		MeetingSchedule schedule = MeetingSchedule.createManaged(
			3L,
			42L,
			"정모",
			"용산역 1번 출구",
			LocalDate.parse("2099-07-10"),
			LocalTime.parse("19:00"),
			LocalTime.parse("21:00"),
			1
		);

		schedule.update("정모", "용산역 1번 출구", LocalDate.parse("2099-07-10"), null, null);

		assertThat(schedule.isTimeUndecided()).isTrue();
		assertThat(schedule.getEndTime()).isNull();
		assertThat(schedule.getStartsAt()).isEqualTo(OffsetDateTime.parse("2099-07-10T00:00:00+09:00"));

		schedule.update("정모", "용산역 1번 출구", LocalDate.parse("2099-07-10"), LocalTime.parse("20:00"), null);

		assertThat(schedule.isTimeUndecided()).isFalse();
		assertThat(schedule.getStartsAt()).isEqualTo(OffsetDateTime.parse("2099-07-10T20:00:00+09:00"));
	}

	private MeetingSchedule schedule() {
		return MeetingSchedule.create(
			3L,
			42L,
			LocalDate.parse("2026-07-10"),
			LocalTime.parse("19:00"),
			null,
			1
		);
	}
}
