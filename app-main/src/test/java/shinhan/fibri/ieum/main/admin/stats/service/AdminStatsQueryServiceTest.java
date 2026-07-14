package shinhan.fibri.ieum.main.admin.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.admin.stats.dto.ContentStatsResponse;
import shinhan.fibri.ieum.main.admin.stats.dto.ReportStatsResponse;
import shinhan.fibri.ieum.main.admin.stats.dto.StatsRangeRequest;
import shinhan.fibri.ieum.main.admin.stats.dto.UserStatsResponse;
import shinhan.fibri.ieum.main.admin.stats.exception.InvalidStatsRangeException;
import shinhan.fibri.ieum.main.admin.stats.repository.AdminStatsQueryRepository;
import shinhan.fibri.ieum.main.admin.stats.repository.AdminStatsQueryRepository.AnswerStatsRow;
import shinhan.fibri.ieum.main.admin.stats.repository.AdminStatsQueryRepository.ReportStatsRow;

class AdminStatsQueryServiceTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final OffsetDateTime FROM_TS = OffsetDateTime.parse("2026-07-01T00:00:00+09:00");
	private static final OffsetDateTime TO_TS = OffsetDateTime.parse("2026-08-01T00:00:00+09:00");

	private AdminStatsQueryRepository repository;
	private AdminStatsQueryService service;

	@BeforeEach
	void setUp() {
		repository = mock(AdminStatsQueryRepository.class);
		Clock clock = Clock.fixed(Instant.parse("2026-07-14T03:00:00Z"), KST);
		service = new AdminStatsQueryService(repository, clock);
	}

	@Test
	void defaultRangeUsesRecentThirtyDaysInKstAndEchoesAppliedDates() {
		OffsetDateTime defaultFromTs = OffsetDateTime.parse("2026-06-15T00:00:00+09:00");
		OffsetDateTime defaultToTs = OffsetDateTime.parse("2026-07-15T00:00:00+09:00");
		when(repository.countSignups(defaultFromTs, defaultToTs)).thenReturn(3L);
		when(repository.countActiveUsers(defaultFromTs, defaultToTs)).thenReturn(2L);
		when(repository.countSuspendedUsers(defaultFromTs, defaultToTs)).thenReturn(1L);

		UserStatsResponse response = service.getUserStats(new StatsRangeRequest(null, null));

		assertThat(response.from()).isEqualTo(LocalDate.of(2026, 6, 15));
		assertThat(response.to()).isEqualTo(LocalDate.of(2026, 7, 14));
		assertThat(response.signupCount()).isEqualTo(3);
		assertThat(response.activeUserCount()).isEqualTo(2);
		assertThat(response.suspendedUserCount()).isEqualTo(1);
	}

	@Test
	void explicitRangeUsesInclusiveLocalDatesAsHalfOpenKstTimestamps() {
		service.getUserStats(new StatsRangeRequest(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)));

		verify(repository).countSignups(FROM_TS, TO_TS);
		verify(repository).countActiveUsers(FROM_TS, TO_TS);
		verify(repository).countSuspendedUsers(FROM_TS, TO_TS);
	}

	@Test
	void fromAfterToIsRejected() {
		StatsRangeRequest request = new StatsRangeRequest(LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 1));

		assertThatThrownBy(() -> service.getUserStats(request))
			.isInstanceOf(InvalidStatsRangeException.class)
			.hasMessage("from must be before or equal to to");
	}

	@Test
	void rangeLongerThanThreeHundredSixtySixDaysIsRejected() {
		StatsRangeRequest request = new StatsRangeRequest(LocalDate.of(2025, 7, 13), LocalDate.of(2026, 7, 14));

		assertThatThrownBy(() -> service.getUserStats(request))
			.isInstanceOf(InvalidStatsRangeException.class)
			.hasMessage("stats range must not exceed 366 days");
	}

	@Test
	void contentStatsCalculateAcceptedRateAndZeroWhenThereAreNoAnswers() {
		when(repository.countPins(FROM_TS, TO_TS)).thenReturn(10L);
		when(repository.countQuestions(FROM_TS, TO_TS)).thenReturn(4L);
		when(repository.countMeetings(FROM_TS, TO_TS)).thenReturn(3L);
		when(repository.getAnswerStats(FROM_TS, TO_TS)).thenReturn(new AnswerStatsRow(4L, 1L));
		when(repository.countMessages(FROM_TS, TO_TS)).thenReturn(12L);

		ContentStatsResponse response = service.getContentStats(new StatsRangeRequest(
			LocalDate.of(2026, 7, 1),
			LocalDate.of(2026, 7, 31)
		));

		assertThat(response.pinCount()).isEqualTo(10);
		assertThat(response.questionCount()).isEqualTo(4);
		assertThat(response.meetingCount()).isEqualTo(3);
		assertThat(response.answerCount()).isEqualTo(4);
		assertThat(response.acceptedRate()).isEqualTo(0.25);
		assertThat(response.messageCount()).isEqualTo(12);

		when(repository.getAnswerStats(FROM_TS, TO_TS)).thenReturn(new AnswerStatsRow(0L, 0L));
		assertThat(service.getContentStats(new StatsRangeRequest(
			LocalDate.of(2026, 7, 1),
			LocalDate.of(2026, 7, 31)
		)).acceptedRate()).isZero();
	}

	@Test
	void reportStatsCombineReportEventsAndSanctionRows() {
		when(repository.getReportStats(FROM_TS, TO_TS)).thenReturn(new ReportStatsRow(5L, 4L, 3L, 2L));
		when(repository.countSanctions(FROM_TS, TO_TS)).thenReturn(7L);

		ReportStatsResponse response = service.getReportStats(new StatsRangeRequest(
			LocalDate.of(2026, 7, 1),
			LocalDate.of(2026, 7, 31)
		));

		assertThat(response.reportCount()).isEqualTo(5);
		assertThat(response.aiReviewedCount()).isEqualTo(4);
		assertThat(response.confirmedCount()).isEqualTo(3);
		assertThat(response.dismissedCount()).isEqualTo(2);
		assertThat(response.sanctionCount()).isEqualTo(7);
	}
}
