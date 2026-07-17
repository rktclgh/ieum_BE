package shinhan.fibri.ieum.main.admin.stats.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.main.admin.stats.dto.AdminStatsDailySeriesResponse;
import shinhan.fibri.ieum.main.admin.stats.dto.AdminStatsOverviewResponse;
import shinhan.fibri.ieum.main.admin.stats.dto.AdminStatsQueueResponse;
import shinhan.fibri.ieum.main.admin.stats.dto.ContentStatsResponse;
import shinhan.fibri.ieum.main.admin.stats.dto.ReportStatsResponse;
import shinhan.fibri.ieum.main.admin.stats.dto.StatsOverviewRequest;
import shinhan.fibri.ieum.main.admin.stats.dto.StatsRangeRequest;
import shinhan.fibri.ieum.main.admin.stats.dto.UserStatsResponse;
import shinhan.fibri.ieum.main.admin.stats.exception.InvalidStatsBucketException;
import shinhan.fibri.ieum.main.admin.stats.exception.InvalidStatsRangeException;
import shinhan.fibri.ieum.main.admin.stats.repository.AdminStatsQueryRepository;
import shinhan.fibri.ieum.main.admin.stats.repository.AdminStatsQueryRepository.AnswerStatsRow;
import shinhan.fibri.ieum.main.admin.stats.repository.AdminStatsQueryRepository.DailyStatsRow;
import shinhan.fibri.ieum.main.admin.stats.repository.AdminStatsQueryRepository.QueueStatsRow;
import shinhan.fibri.ieum.main.admin.stats.repository.AdminStatsQueryRepository.ReportStatsRow;
import shinhan.fibri.ieum.main.admin.stats.repository.AdminStatsQueryRepository.SummaryStatsRow;

@Service
public class AdminStatsQueryService {

	static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final long MAX_RANGE_DAYS = 366;

	private final AdminStatsQueryRepository repository;
	private final Clock clock;

	@Autowired
	public AdminStatsQueryService(AdminStatsQueryRepository repository) {
		this(repository, Clock.system(KST));
	}

	AdminStatsQueryService(AdminStatsQueryRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public UserStatsResponse getUserStats(StatsRangeRequest request) {
		ResolvedRange range = resolveRange(request);
		return new UserStatsResponse(
			range.from(),
			range.to(),
			repository.countSignups(range.fromTs(), range.toTs()),
			repository.countActiveUsers(range.fromTs(), range.toTs()),
			repository.countSuspendedUsers(range.fromTs(), range.toTs())
		);
	}

	@Transactional(readOnly = true)
	public ContentStatsResponse getContentStats(StatsRangeRequest request) {
		ResolvedRange range = resolveRange(request);
		AnswerStatsRow answerStats = repository.getAnswerStats(range.fromTs(), range.toTs());
		double acceptedRate = answerStats.userTotal() == 0 ? 0.0 : (double)answerStats.accepted() / answerStats.userTotal();
		return new ContentStatsResponse(
			range.from(),
			range.to(),
			repository.countPins(range.fromTs(), range.toTs()),
			repository.countQuestions(range.fromTs(), range.toTs()),
			repository.countMeetings(range.fromTs(), range.toTs()),
			answerStats.total(),
			acceptedRate,
			repository.countMessages(range.fromTs(), range.toTs())
		);
	}

	@Transactional(readOnly = true)
	public ReportStatsResponse getReportStats(StatsRangeRequest request) {
		ResolvedRange range = resolveRange(request);
		ReportStatsRow reportStats = repository.getReportStats(range.fromTs(), range.toTs());
		return new ReportStatsResponse(
			range.from(),
			range.to(),
			reportStats.reportCount(),
			reportStats.aiReviewedCount(),
			reportStats.confirmedCount(),
			reportStats.dismissedCount(),
			repository.countSanctions(range.fromTs(), range.toTs())
		);
	}

	@Transactional(readOnly = true)
	public AdminStatsOverviewResponse getOverview(StatsOverviewRequest request) {
		String bucket = request.bucket() == null ? "day" : request.bucket();
		if (!"day".equals(bucket)) {
			throw new InvalidStatsBucketException("bucket must be day");
		}

		ResolvedRange range = resolveRange(new StatsRangeRequest(request.from(), request.to()));
		SummaryStatsRow summary = repository.getOverviewSummary(range.fromTs(), range.toTs());
		Map<LocalDate, DailyStatsRow> rowsByDate = repository.findDailyStats(range.fromTs(), range.toTs())
			.stream()
			.collect(Collectors.toMap(DailyStatsRow::date, Function.identity()));
		QueueStatsRow queues = repository.getCurrentQueues();
		return new AdminStatsOverviewResponse(
			range.from(),
			range.to(),
			bucket,
			toSummary(summary),
			dateSpine(range.from(), range.to(), rowsByDate),
			new AdminStatsQueueResponse(
				queues.pendingReportCount(),
				queues.retryReportCount(),
				queues.deadReportCount(),
				queues.pendingInquiryCount()
			)
		);
	}

	private AdminStatsOverviewResponse.Summary toSummary(SummaryStatsRow row) {
		double acceptedRate = row.humanAnswerCount() == 0
			? 0.0
			: (double)row.acceptedHumanAnswerCount() / row.humanAnswerCount();
		return new AdminStatsOverviewResponse.Summary(
			row.signupCount(),
			row.activeUserCount(),
			row.suspensionCount(),
			row.questionCount(),
			row.humanAnswerCount(),
			row.acceptedHumanAnswerCount(),
			acceptedRate,
			row.reportCount(),
			row.aiReviewedCount(),
			row.confirmedCount(),
			row.dismissedCount(),
			row.sanctionCount()
		);
	}

	private List<AdminStatsDailySeriesResponse> dateSpine(
		LocalDate from,
		LocalDate to,
		Map<LocalDate, DailyStatsRow> rowsByDate
	) {
		return from.datesUntil(to.plusDays(1))
			.map(date -> toDailySeries(date, rowsByDate.get(date)))
			.toList();
	}

	private AdminStatsDailySeriesResponse toDailySeries(LocalDate date, DailyStatsRow row) {
		if (row == null) {
			return new AdminStatsDailySeriesResponse(date, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
		}
		return new AdminStatsDailySeriesResponse(
			date,
			row.signupCount(),
			row.activeUserCount(),
			row.questionCount(),
			row.humanAnswerCount(),
			row.acceptedHumanAnswerCount(),
			row.reportCount(),
			row.aiReviewedCount(),
			row.confirmedCount(),
			row.dismissedCount(),
			row.sanctionCount()
		);
	}

	private ResolvedRange resolveRange(StatsRangeRequest request) {
		LocalDate today = LocalDate.now(clock);
		LocalDate to = request.to() == null ? today : request.to();
		LocalDate from = request.from() == null ? to.minusDays(29) : request.from();
		if (from.isAfter(to)) {
			throw new InvalidStatsRangeException("from must be before or equal to to");
		}
		long inclusiveDays = ChronoUnit.DAYS.between(from, to) + 1;
		if (inclusiveDays > MAX_RANGE_DAYS) {
			throw new InvalidStatsRangeException("stats range must not exceed 366 days");
		}
		OffsetDateTime fromTs = from.atStartOfDay(KST).toOffsetDateTime();
		OffsetDateTime toTs = to.plusDays(1).atStartOfDay(KST).toOffsetDateTime();
		return new ResolvedRange(from, to, fromTs, toTs);
	}

	private record ResolvedRange(
		LocalDate from,
		LocalDate to,
		OffsetDateTime fromTs,
		OffsetDateTime toTs
	) {
	}
}
