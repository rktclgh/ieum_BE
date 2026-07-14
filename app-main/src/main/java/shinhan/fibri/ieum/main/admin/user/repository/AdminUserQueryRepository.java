package shinhan.fibri.ieum.main.admin.user.repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminUserQueryRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public AdminUserQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<AdminUserRow> findUsers(String status, String qLike, Long cursorId, int limit) {
		String sql = """
			SELECT u.user_id, u.email, u.nickname, u.role, u.status, u.grade, u.provider, u.last_active_at
			FROM users u
			WHERE u.deleted_at IS NULL
			  AND (:status IS NULL OR CAST(u.status AS varchar) = :status)
			  AND (:qLike IS NULL OR lower(u.nickname) LIKE :qLike ESCAPE '\\'
			                      OR lower(u.email) LIKE :qLike ESCAPE '\\')
			  AND (:cursorId IS NULL OR u.user_id < :cursorId)
			ORDER BY u.user_id DESC
			LIMIT :limit
			""";
		// 세 파라미터 모두 "(:x IS NULL OR ...)" 형태로만 쓰이는데, null일 때 SQL 타입을 명시하지
		// 않으면 postgres가 파라미터 타입을 추론하지 못해 "could not determine data type of parameter"
		// 로 500이 난다. 값이 null이어도 항상 타입을 명시해야 한다.
		MapSqlParameterSource params = new MapSqlParameterSource()
			.addValue("status", status, Types.VARCHAR)
			.addValue("qLike", qLike, Types.VARCHAR)
			.addValue("cursorId", cursorId, Types.BIGINT)
			.addValue("limit", limit);
		return jdbcTemplate.query(sql, params, userRowMapper());
	}

	public List<AdminReportRow> findReports(Long userId, int limit) {
		String sql = """
			SELECT r.report_id, r.reason, r.status, r.reporter_id, reporter.nickname AS reporter_nickname,
			       r.message_id, r.detail, r.created_at
			FROM reports r
			LEFT JOIN users reporter ON reporter.user_id = r.reporter_id AND reporter.deleted_at IS NULL
			WHERE r.reported_user_id = :userId
			ORDER BY r.created_at DESC, r.report_id DESC
			LIMIT :limit
			""";
		return jdbcTemplate.query(
			sql,
			Map.of("userId", userId, "limit", limit),
			(rs, rowNum) -> new AdminReportRow(
				rs.getLong("report_id"),
				rs.getString("reason"),
				rs.getString("status"),
				rs.getLong("reporter_id"),
				rs.getString("reporter_nickname"),
				longObject(rs.getObject("message_id")),
				rs.getString("detail"),
				rs.getObject("created_at", OffsetDateTime.class)
			)
		);
	}

	public int countQuestions(Long userId) {
		return count("SELECT COUNT(*) FROM questions WHERE author_id = :userId", userId);
	}

	public int countAnswers(Long userId) {
		return count("SELECT COUNT(*) FROM answers WHERE author_id = :userId", userId);
	}

	public int countReports(Long userId) {
		return count("SELECT COUNT(*) FROM reports WHERE reported_user_id = :userId", userId);
	}

	private int count(String sql, Long userId) {
		Integer result = jdbcTemplate.queryForObject(sql, Map.of("userId", userId), Integer.class);
		return result == null ? 0 : result;
	}

	private RowMapper<AdminUserRow> userRowMapper() {
		return (rs, rowNum) -> new AdminUserRow(
			rs.getLong("user_id"),
			rs.getString("email"),
			rs.getString("nickname"),
			rs.getString("role"),
			rs.getString("status"),
			rs.getString("grade"),
			rs.getString("provider"),
			rs.getObject("last_active_at", OffsetDateTime.class)
		);
	}

	private static Long longObject(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	public record AdminUserRow(
		Long userId,
		String email,
		String nickname,
		String role,
		String status,
		String grade,
		String provider,
		OffsetDateTime lastActiveAt
	) {
	}

	public record AdminReportRow(
		Long reportId,
		String reason,
		String status,
		Long reporterId,
		String reporterNickname,
		Long messageId,
		String detail,
		OffsetDateTime createdAt
	) {
	}
}
