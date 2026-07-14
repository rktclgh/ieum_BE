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

	public long countPins(OffsetDateTime from, OffsetDateTime to) {
		return count("SELECT COUNT(*) FROM pins WHERE created_at >= :from AND created_at < :to", from, to);
	}

	public long countQuestions(OffsetDateTime from, OffsetDateTime to) {
		return count("SELECT COUNT(*) FROM questions WHERE created_at >= :from AND created_at < :to", from, to);
	}

	public long countMeetings(OffsetDateTime from, OffsetDateTime to) {
		return count("SELECT COUNT(*) FROM meetings WHERE created_at >= :from AND created_at < :to", from, to);
	}

	public long countMessages(OffsetDateTime from, OffsetDateTime to) {
		return count("SELECT COUNT(*) FROM messages WHERE created_at >= :from AND created_at < :to", from, to);
	}

	public AnswerStatsRow getAnswerStats(OffsetDateTime from, OffsetDateTime to) {
		String sql = """
			SELECT COUNT(*) AS total,
			       COUNT(*) FILTER (WHERE is_accepted) AS accepted
			FROM answers
			WHERE created_at >= :from AND created_at < :to
			""";
		return jdbcTemplate.queryForObject(sql, rangeParams(from, to), (rs, rowNum) -> new AnswerStatsRow(
			rs.getLong("total"),
			rs.getLong("accepted")
		));
	}

	public ReportStatsRow getReportStats(OffsetDateTime from, OffsetDateTime to) {
		String sql = """
			SELECT
			  COUNT(*) FILTER (WHERE created_at >= :from AND created_at < :to) AS report_count,
			  COUNT(*) FILTER (
			  	WHERE ai_reviewed_at >= :from
			  	  AND ai_reviewed_at < :to
			  	  AND CAST(ai_review_state AS varchar) = 'completed'
			  ) AS ai_reviewed_count,
			  COUNT(*) FILTER (
			  	WHERE resolved_at >= :from
			  	  AND resolved_at < :to
			  	  AND CAST(status AS varchar) = 'confirmed'
			  ) AS confirmed_count,
			  COUNT(*) FILTER (
			  	WHERE resolved_at >= :from
			  	  AND resolved_at < :to
			  	  AND CAST(status AS varchar) = 'dismissed'
			  ) AS dismissed_count
			FROM reports
			""";
		return jdbcTemplate.queryForObject(sql, rangeParams(from, to), (rs, rowNum) -> new ReportStatsRow(
			rs.getLong("report_count"),
			rs.getLong("ai_reviewed_count"),
			rs.getLong("confirmed_count"),
			rs.getLong("dismissed_count")
		));
	}

	public long countSanctions(OffsetDateTime from, OffsetDateTime to) {
		return count("SELECT COUNT(*) FROM user_sanctions WHERE created_at >= :from AND created_at < :to", from, to);
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

	public record AnswerStatsRow(long total, long accepted) {
	}

	public record ReportStatsRow(
		long reportCount,
		long aiReviewedCount,
		long confirmedCount,
		long dismissedCount
	) {
	}
}
