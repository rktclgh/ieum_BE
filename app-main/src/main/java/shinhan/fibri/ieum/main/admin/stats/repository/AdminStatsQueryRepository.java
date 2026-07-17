package shinhan.fibri.ieum.main.admin.stats.repository;

import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminStatsQueryRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public AdminStatsQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// 유저 지표는 "관측 시점에 살아있던 유저" 기준으로 집계한다.
	// 단순 deleted_at IS NULL 필터는 이후 탈퇴 시 과거 통계가 소급 감소하는
	// 데이터 불일치를 만들므로, 탈퇴 시각과 관측 시각을 비교해 판정한다.
	public long countSignups(OffsetDateTime from, OffsetDateTime to) {
		return count("""
			SELECT COUNT(*)
			FROM users
			WHERE created_at >= :from
			  AND created_at < :to
			  AND (deleted_at IS NULL OR deleted_at >= :to)
			""", from, to);
	}

	public long countActiveUsers(OffsetDateTime from, OffsetDateTime to) {
		return count("""
			SELECT COUNT(DISTINCT l.user_id)
			FROM login_logs l
			JOIN users u ON u.user_id = l.user_id
			WHERE l.logged_in_at >= :from
			  AND l.logged_in_at < :to
			  AND (u.deleted_at IS NULL OR l.logged_in_at < u.deleted_at)
			""", from, to);
	}

	public long countSuspendedUsers(OffsetDateTime from, OffsetDateTime to) {
		return count("""
			SELECT COUNT(DISTINCT s.user_id)
			FROM user_sanctions s
			JOIN users u ON u.user_id = s.user_id
			WHERE s.created_at >= :from
			  AND s.created_at < :to
			  AND (u.deleted_at IS NULL OR s.created_at < u.deleted_at)
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
			       COUNT(*) FILTER (WHERE NOT is_ai) AS user_total,
			       COUNT(*) FILTER (WHERE NOT is_ai AND is_accepted) AS accepted
			FROM answers
			WHERE created_at >= :from AND created_at < :to
			""";
		return jdbcTemplate.queryForObject(sql, rangeParams(from, to), (rs, rowNum) -> new AnswerStatsRow(
			rs.getLong("total"),
			rs.getLong("user_total"),
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
			WHERE (created_at >= :from AND created_at < :to)
			   OR (ai_reviewed_at >= :from AND ai_reviewed_at < :to)
			   OR (resolved_at >= :from AND resolved_at < :to)
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

	public SummaryStatsRow getOverviewSummary(OffsetDateTime from, OffsetDateTime to) {
		String sql = """
			SELECT
			  (SELECT COUNT(*)
			   FROM users u
			   WHERE u.created_at >= :from
			     AND u.created_at < :to
			     AND (u.deleted_at IS NULL OR u.deleted_at >= :to)) AS signup_count,
			  (SELECT COUNT(DISTINCT l.user_id)
			   FROM login_logs l
			   JOIN users u ON u.user_id = l.user_id
			   WHERE l.logged_in_at >= :from
			     AND l.logged_in_at < :to
			     AND (u.deleted_at IS NULL OR l.logged_in_at < u.deleted_at)) AS active_user_count,
			  (SELECT COUNT(DISTINCT s.user_id)
			   FROM user_sanctions s
			   JOIN users u ON u.user_id = s.user_id
			   WHERE s.created_at >= :from
			     AND s.created_at < :to
			     AND (u.deleted_at IS NULL OR s.created_at < u.deleted_at)) AS suspension_count,
			  (SELECT COUNT(*) FROM questions q WHERE q.created_at >= :from AND q.created_at < :to) AS question_count,
			  (SELECT COUNT(*) FROM answers a WHERE a.created_at >= :from AND a.created_at < :to AND NOT a.is_ai) AS human_answer_count,
			  (SELECT COUNT(*) FROM answers a WHERE a.created_at >= :from AND a.created_at < :to AND NOT a.is_ai AND a.is_accepted) AS accepted_human_answer_count,
			  (SELECT COUNT(*) FROM reports r WHERE r.created_at >= :from AND r.created_at < :to) AS report_count,
			  (SELECT COUNT(*) FROM reports r
			   WHERE r.ai_reviewed_at >= :from
			     AND r.ai_reviewed_at < :to
			     AND CAST(r.ai_review_state AS varchar) = 'completed') AS ai_reviewed_count,
			  (SELECT COUNT(*) FROM reports r
			   WHERE r.resolved_at >= :from
			     AND r.resolved_at < :to
			     AND CAST(r.status AS varchar) = 'confirmed') AS confirmed_count,
			  (SELECT COUNT(*) FROM reports r
			   WHERE r.resolved_at >= :from
			     AND r.resolved_at < :to
			     AND CAST(r.status AS varchar) = 'dismissed') AS dismissed_count,
			  (SELECT COUNT(*) FROM user_sanctions s WHERE s.created_at >= :from AND s.created_at < :to) AS sanction_count
			""";
		return jdbcTemplate.queryForObject(sql, rangeParams(from, to), (rs, rowNum) -> new SummaryStatsRow(
			rs.getLong("signup_count"),
			rs.getLong("active_user_count"),
			rs.getLong("suspension_count"),
			rs.getLong("question_count"),
			rs.getLong("human_answer_count"),
			rs.getLong("accepted_human_answer_count"),
			rs.getLong("report_count"),
			rs.getLong("ai_reviewed_count"),
			rs.getLong("confirmed_count"),
			rs.getLong("dismissed_count"),
			rs.getLong("sanction_count")
		));
	}

	public List<DailyStatsRow> findDailyStats(OffsetDateTime from, OffsetDateTime to) {
		String sql = """
			WITH signups AS (
			  SELECT (u.created_at AT TIME ZONE 'Asia/Seoul')::date AS date, COUNT(*) AS count
			  FROM users u
			  WHERE u.created_at >= :from
			    AND u.created_at < :to
			    AND (u.deleted_at IS NULL OR u.deleted_at >= :to)
			  GROUP BY 1
			),
			active_users AS (
			  SELECT (l.logged_in_at AT TIME ZONE 'Asia/Seoul')::date AS date, COUNT(DISTINCT l.user_id) AS count
			  FROM login_logs l
			  JOIN users u ON u.user_id = l.user_id
			  WHERE l.logged_in_at >= :from
			    AND l.logged_in_at < :to
			    AND (u.deleted_at IS NULL OR l.logged_in_at < u.deleted_at)
			  GROUP BY 1
			),
			questions AS (
			  SELECT (q.created_at AT TIME ZONE 'Asia/Seoul')::date AS date, COUNT(*) AS count
			  FROM questions q
			  WHERE q.created_at >= :from AND q.created_at < :to
			  GROUP BY 1
			),
			human_answers AS (
			  SELECT
			    (a.created_at AT TIME ZONE 'Asia/Seoul')::date AS date,
			    COUNT(*) AS count,
			    COUNT(*) FILTER (WHERE a.is_accepted) AS accepted_count
			  FROM answers a
			  WHERE a.created_at >= :from
			    AND a.created_at < :to
			    AND NOT a.is_ai
			  GROUP BY 1
			),
			reports_created AS (
			  SELECT (r.created_at AT TIME ZONE 'Asia/Seoul')::date AS date, COUNT(*) AS count
			  FROM reports r
			  WHERE r.created_at >= :from AND r.created_at < :to
			  GROUP BY 1
			),
			ai_reviewed AS (
			  SELECT (r.ai_reviewed_at AT TIME ZONE 'Asia/Seoul')::date AS date, COUNT(*) AS count
			  FROM reports r
			  WHERE r.ai_reviewed_at >= :from
			    AND r.ai_reviewed_at < :to
			    AND CAST(r.ai_review_state AS varchar) = 'completed'
			  GROUP BY 1
			),
			confirmed AS (
			  SELECT (r.resolved_at AT TIME ZONE 'Asia/Seoul')::date AS date, COUNT(*) AS count
			  FROM reports r
			  WHERE r.resolved_at >= :from
			    AND r.resolved_at < :to
			    AND CAST(r.status AS varchar) = 'confirmed'
			  GROUP BY 1
			),
			dismissed AS (
			  SELECT (r.resolved_at AT TIME ZONE 'Asia/Seoul')::date AS date, COUNT(*) AS count
			  FROM reports r
			  WHERE r.resolved_at >= :from
			    AND r.resolved_at < :to
			    AND CAST(r.status AS varchar) = 'dismissed'
			  GROUP BY 1
			),
			sanctions AS (
			  SELECT (s.created_at AT TIME ZONE 'Asia/Seoul')::date AS date, COUNT(*) AS count
			  FROM user_sanctions s
			  WHERE s.created_at >= :from AND s.created_at < :to
			  GROUP BY 1
			),
			dates AS (
			  SELECT date FROM signups
			  UNION SELECT date FROM active_users
			  UNION SELECT date FROM questions
			  UNION SELECT date FROM human_answers
			  UNION SELECT date FROM reports_created
			  UNION SELECT date FROM ai_reviewed
			  UNION SELECT date FROM confirmed
			  UNION SELECT date FROM dismissed
			  UNION SELECT date FROM sanctions
			)
			SELECT
			  d.date,
			  COALESCE(signups.count, 0) AS signup_count,
			  COALESCE(active_users.count, 0) AS active_user_count,
			  COALESCE(questions.count, 0) AS question_count,
			  COALESCE(human_answers.count, 0) AS human_answer_count,
			  COALESCE(human_answers.accepted_count, 0) AS accepted_human_answer_count,
			  COALESCE(reports_created.count, 0) AS report_count,
			  COALESCE(ai_reviewed.count, 0) AS ai_reviewed_count,
			  COALESCE(confirmed.count, 0) AS confirmed_count,
			  COALESCE(dismissed.count, 0) AS dismissed_count,
			  COALESCE(sanctions.count, 0) AS sanction_count
			FROM dates d
			LEFT JOIN signups ON signups.date = d.date
			LEFT JOIN active_users ON active_users.date = d.date
			LEFT JOIN questions ON questions.date = d.date
			LEFT JOIN human_answers ON human_answers.date = d.date
			LEFT JOIN reports_created ON reports_created.date = d.date
			LEFT JOIN ai_reviewed ON ai_reviewed.date = d.date
			LEFT JOIN confirmed ON confirmed.date = d.date
			LEFT JOIN dismissed ON dismissed.date = d.date
			LEFT JOIN sanctions ON sanctions.date = d.date
			ORDER BY d.date
			""";
		return jdbcTemplate.query(sql, rangeParams(from, to), (rs, rowNum) -> new DailyStatsRow(
			rs.getObject("date", LocalDate.class),
			rs.getLong("signup_count"),
			rs.getLong("active_user_count"),
			rs.getLong("question_count"),
			rs.getLong("human_answer_count"),
			rs.getLong("accepted_human_answer_count"),
			rs.getLong("report_count"),
			rs.getLong("ai_reviewed_count"),
			rs.getLong("confirmed_count"),
			rs.getLong("dismissed_count"),
			rs.getLong("sanction_count")
		));
	}

	public QueueStatsRow getCurrentQueues() {
		String sql = """
			SELECT
			  (SELECT COUNT(*) FROM reports r WHERE CAST(r.status AS varchar) = 'pending') AS pending_report_count,
			  (SELECT COUNT(*) FROM reports r
			   WHERE CAST(r.ai_review_state AS varchar) = 'retry'
			     AND CAST(r.status AS varchar) IN ('pending', 'ai_reviewed')) AS retry_report_count,
			  (SELECT COUNT(*) FROM reports r
			   WHERE CAST(r.ai_review_state AS varchar) = 'dead'
			     AND CAST(r.status AS varchar) IN ('pending', 'ai_reviewed')) AS dead_report_count,
			  (SELECT COUNT(*) FROM inquiries i WHERE CAST(i.status AS varchar) = 'pending') AS pending_inquiry_count
			""";
		return jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), (rs, rowNum) -> new QueueStatsRow(
			rs.getLong("pending_report_count"),
			rs.getLong("retry_report_count"),
			rs.getLong("dead_report_count"),
			rs.getLong("pending_inquiry_count")
		));
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

	public record AnswerStatsRow(long total, long userTotal, long accepted) {
	}

	public record ReportStatsRow(
		long reportCount,
		long aiReviewedCount,
		long confirmedCount,
		long dismissedCount
	) {
	}

	public record SummaryStatsRow(
		long signupCount,
		long activeUserCount,
		long suspensionCount,
		long questionCount,
		long humanAnswerCount,
		long acceptedHumanAnswerCount,
		long reportCount,
		long aiReviewedCount,
		long confirmedCount,
		long dismissedCount,
		long sanctionCount
	) {
	}

	public record DailyStatsRow(
		LocalDate date,
		long signupCount,
		long activeUserCount,
		long questionCount,
		long humanAnswerCount,
		long acceptedHumanAnswerCount,
		long reportCount,
		long aiReviewedCount,
		long confirmedCount,
		long dismissedCount,
		long sanctionCount
	) {
	}

	public record QueueStatsRow(
		long pendingReportCount,
		long retryReportCount,
		long deadReportCount,
		long pendingInquiryCount
	) {
	}
}
