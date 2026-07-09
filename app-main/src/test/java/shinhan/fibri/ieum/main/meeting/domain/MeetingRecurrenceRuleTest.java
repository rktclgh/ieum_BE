package shinhan.fibri.ieum.main.meeting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MeetingRecurrenceRuleTest {

	@Test
	void createWeeklyRuleRequiresDaysOfWeek() {
		assertThatThrownBy(() -> MeetingRecurrenceRule.createWeekly(
			3L,
			1,
			List.of(),
			LocalDate.parse("2026-07-10"),
			LocalDate.parse("2026-09-30"),
			null,
			"Asia/Seoul"
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("daysOfWeek is required for weekly recurrence");
	}

	@Test
	void createMonthlyRuleRequiresDayOfMonth() {
		assertThatThrownBy(() -> MeetingRecurrenceRule.createMonthly(
			3L,
			1,
			null,
			LocalDate.parse("2026-07-10"),
			null,
			12,
			"Asia/Seoul"
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("dayOfMonth is required for monthly recurrence");
	}

	@Test
	void createDailyRuleInitializesDefaults() {
		MeetingRecurrenceRule rule = MeetingRecurrenceRule.createDaily(
			3L,
			2,
			LocalDate.parse("2026-07-10"),
			null,
			12,
			null
		);

		assertThat(rule.getMeetingId()).isEqualTo(3L);
		assertThat(rule.getFrequency()).isEqualTo(RecurrenceFrequency.daily);
		assertThat(rule.getIntervalValue()).isEqualTo(2);
		assertThat(rule.getTimezone()).isEqualTo("Asia/Seoul");
		assertThat(rule.getMaxOccurrences()).isEqualTo(12);
	}

	@Test
	void getDaysOfWeekReturnsDefensiveCopy() {
		MeetingRecurrenceRule rule = MeetingRecurrenceRule.createWeekly(
			3L,
			1,
			List.of(1, 2),
			LocalDate.parse("2026-07-10"),
			null,
			null,
			"Asia/Seoul"
		);

		Short[] returned = rule.getDaysOfWeek();
		returned[0] = 7;

		assertThat(rule.getDaysOfWeek()).containsExactly((short) 1, (short) 2);
	}
}
