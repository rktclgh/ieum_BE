package shinhan.fibri.ieum.main.admin.stats.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.main.admin.stats.dto.StatsRangeRequest;
import shinhan.fibri.ieum.main.admin.stats.dto.UserStatsResponse;
import shinhan.fibri.ieum.main.admin.stats.exception.InvalidStatsRangeException;
import shinhan.fibri.ieum.main.admin.stats.repository.AdminStatsQueryRepository;

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
