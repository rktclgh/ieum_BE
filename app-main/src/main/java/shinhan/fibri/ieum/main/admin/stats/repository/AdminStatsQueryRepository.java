package shinhan.fibri.ieum.main.admin.stats.repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminStatsQueryRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public AdminStatsQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public long countSignups(OffsetDateTime from, OffsetDateTime to) {
		return count("SELECT COUNT(*) FROM users WHERE created_at >= :from AND created_at < :to", from, to);
	}

	public long countActiveUsers(OffsetDateTime from, OffsetDateTime to) {
		return count("""
			SELECT COUNT(DISTINCT user_id)
			FROM login_logs
			WHERE logged_in_at >= :from AND logged_in_at < :to
			""", from, to);
	}

	public long countSuspendedUsers(OffsetDateTime from, OffsetDateTime to) {
		return count("""
			SELECT COUNT(DISTINCT user_id)
			FROM user_sanctions
			WHERE created_at >= :from AND created_at < :to
			""", from, to);
	}

	private long count(String sql, OffsetDateTime from, OffsetDateTime to) {
		Long result = jdbcTemplate.queryForObject(sql, rangeParams(from, to), Long.class);
		return result == null ? 0 : result;
	}

	private MapSqlParameterSource rangeParams(OffsetDateTime from, OffsetDateTime to) {
		return new MapSqlParameterSource()
			.addValue("from", from, Types.TIMESTAMP_WITH_TIMEZONE)
			.addValue("to", to, Types.TIMESTAMP_WITH_TIMEZONE);
	}
}
