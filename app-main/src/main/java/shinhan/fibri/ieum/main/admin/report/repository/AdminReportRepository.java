package shinhan.fibri.ieum.main.admin.report.repository;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import shinhan.fibri.ieum.main.admin.report.service.AdminReportCursor;

@Repository
public class AdminReportRepository {

	private static final String LIST_SELECT = """
		SELECT r.report_id,
		       CAST(r.target_type AS varchar) AS target_type,
		       CASE
		           WHEN r.target_type = 'message' THEN COALESCE(
		               r.message_id,
		               CAST(NULLIF(r.context_snapshot #>> '{reported,messageId}', '') AS bigint)
		           )
		           WHEN r.target_type = 'answer' THEN COALESCE(
		               r.answer_id,
		               CAST(NULLIF(r.context_snapshot #>> '{reported,answerId}', '') AS bigint)
		           )
		           ELSE COALESCE(
		               r.schedule_id,
		               CAST(NULLIF(r.context_snapshot #>> '{reported,scheduleId}', '') AS bigint)
		           )
		       END AS target_id,
		       CASE
		           WHEN r.target_type = 'message' THEN r.message_id IS NULL
		           WHEN r.target_type = 'answer' THEN r.answer_id IS NULL
		           ELSE r.schedule_id IS NULL
		       END AS target_deleted,
		       r.reporter_id,
		       reporter.nickname AS reporter_nickname,
		       r.reported_user_id,
		       reported.nickname AS reported_user_nickname,
		       CAST(r.reason AS varchar) AS reason,
		       CAST(r.status AS varchar) AS status,
		       CAST(r.ai_review_state AS varchar) AS ai_review_state,
		       CAST(r.ai_recommendation AS varchar) AS ai_recommendation,
		       CAST(r.ai_decision AS varchar) AS ai_decision,
		       r.ai_confidence,
		       r.ai_reviewed_at,
		       r.created_at
		FROM reports r
		JOIN users reporter ON reporter.user_id = r.reporter_id
		LEFT JOIN users reported ON reported.user_id = r.reported_user_id
		WHERE 1 = 1
		""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public AdminReportRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<AdminReportListRow> findReports(
		String status,
		String aiReviewState,
		String decision,
		AdminReportCursor.Position cursor,
		int limit
	) {
		StringBuilder sql = new StringBuilder(LIST_SELECT);
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
		if (status != null) {
			sql.append(" AND r.status = CAST(:status AS report_status)");
			params.addValue("status", status, Types.VARCHAR);
		}
		if (aiReviewState != null) {
			sql.append(" AND r.ai_review_state = CAST(:aiReviewState AS ai_job_status)");
			params.addValue("aiReviewState", aiReviewState, Types.VARCHAR);
		}
		if (decision != null) {
			sql.append(" AND r.ai_decision = CAST(:decision AS ai_report_decision)");
			params.addValue("decision", decision, Types.VARCHAR);
		}
		if (cursor != null) {
			sql.append("""
				 AND (
				     r.created_at < :cursorCreatedAt
				     OR (r.created_at = :cursorCreatedAt AND r.report_id < :cursorReportId)
				 )
				""");
			params.addValue("cursorCreatedAt", cursor.createdAt(), Types.TIMESTAMP_WITH_TIMEZONE);
			params.addValue("cursorReportId", cursor.reportId(), Types.BIGINT);
		}
		sql.append(" ORDER BY r.created_at DESC, r.report_id DESC LIMIT :limit");
		return jdbcTemplate.query(sql.toString(), params, (rs, rowNumber) -> new AdminReportListRow(
			rs.getLong("report_id"),
			rs.getString("target_type"),
			longObject(rs.getObject("target_id")),
			rs.getBoolean("target_deleted"),
			rs.getLong("reporter_id"),
			rs.getString("reporter_nickname"),
			longObject(rs.getObject("reported_user_id")),
			rs.getString("reported_user_nickname"),
			rs.getString("reason"),
			rs.getString("status"),
			rs.getString("ai_review_state"),
			rs.getString("ai_recommendation"),
			rs.getString("ai_decision"),
			rs.getBigDecimal("ai_confidence"),
			rs.getObject("ai_reviewed_at", OffsetDateTime.class),
			rs.getObject("created_at", OffsetDateTime.class)
		));
	}

	public Optional<AdminReportDetailRow> findDetail(Long reportId) {
		String sql = """
			SELECT r.report_id,
			       CAST(r.target_type AS varchar) AS target_type,
			       CASE
			           WHEN r.target_type = 'message' THEN COALESCE(
			               r.message_id,
			               CAST(NULLIF(r.context_snapshot #>> '{reported,messageId}', '') AS bigint)
			           )
			           WHEN r.target_type = 'answer' THEN COALESCE(
			               r.answer_id,
			               CAST(NULLIF(r.context_snapshot #>> '{reported,answerId}', '') AS bigint)
			           )
			           ELSE COALESCE(
			               r.schedule_id,
			               CAST(NULLIF(r.context_snapshot #>> '{reported,scheduleId}', '') AS bigint)
			           )
			       END AS target_id,
			       CASE
			           WHEN r.target_type = 'message' THEN r.message_id IS NULL
			           WHEN r.target_type = 'answer' THEN r.answer_id IS NULL
			           ELSE r.schedule_id IS NULL
			       END
			           AS target_deleted,
			       r.reporter_id, reporter.nickname AS reporter_nickname,
			       r.reported_user_id, reported.nickname AS reported_user_nickname,
			       CAST(r.reason AS varchar) AS reason, r.detail, CAST(r.status AS varchar) AS status,
			       CAST(r.context_snapshot AS text) AS context_snapshot, r.context_hash,
			       CAST(r.ai_review_state AS varchar) AS ai_review_state,
			       CAST(r.ai_recommendation AS varchar) AS ai_recommendation,
			       r.ai_reason, r.ai_confidence, r.ai_model_version, r.ai_policy_version, r.ai_reviewed_at,
			       CAST(r.ai_decision AS varchar) AS ai_decision, r.ai_policy_set_hash,
			       CAST(r.ai_review_result AS text) AS ai_review_result, r.ai_last_error_code,
			       r.resolved_by, resolver.nickname AS resolved_by_nickname, r.resolved_at, r.created_at
			FROM reports r
			JOIN users reporter ON reporter.user_id = r.reporter_id
			LEFT JOIN users reported ON reported.user_id = r.reported_user_id
			LEFT JOIN users resolver ON resolver.user_id = r.resolved_by
			WHERE r.report_id = :reportId
			""";
		return jdbcTemplate.query(sql, Map.of("reportId", reportId), (rs, rowNumber) -> new AdminReportDetailRow(
			rs.getLong("report_id"),
			rs.getString("target_type"),
			longObject(rs.getObject("target_id")),
			rs.getBoolean("target_deleted"),
			rs.getLong("reporter_id"),
			rs.getString("reporter_nickname"),
			longObject(rs.getObject("reported_user_id")),
			rs.getString("reported_user_nickname"),
			rs.getString("reason"),
			rs.getString("detail"),
			rs.getString("status"),
			rs.getString("context_snapshot"),
			rs.getString("context_hash"),
			rs.getString("ai_review_state"),
			rs.getString("ai_recommendation"),
			rs.getString("ai_reason"),
			rs.getBigDecimal("ai_confidence"),
			rs.getString("ai_model_version"),
			rs.getString("ai_policy_version"),
			rs.getObject("ai_reviewed_at", OffsetDateTime.class),
			rs.getString("ai_decision"),
			rs.getString("ai_policy_set_hash"),
			rs.getString("ai_review_result"),
			rs.getString("ai_last_error_code"),
			longObject(rs.getObject("resolved_by")),
			rs.getString("resolved_by_nickname"),
			rs.getObject("resolved_at", OffsetDateTime.class),
			rs.getObject("created_at", OffsetDateTime.class)
		)).stream().findFirst();
	}

	public List<AdminReportSanctionRow> findSanctions(Long reportId) {
		String sql = """
			SELECT s.sanction_id, CAST(s.decision_source AS varchar) AS decision_source,
			       CAST(s.sanction_type AS varchar) AS sanction_type, s.reason,
			       s.admin_id, admin.nickname AS admin_nickname,
			       s.starts_at, s.ends_at, s.released_at,
			       s.released_by, releaser.nickname AS released_by_nickname, s.created_at
			FROM user_sanctions s
			LEFT JOIN users admin ON admin.user_id = s.admin_id
			LEFT JOIN users releaser ON releaser.user_id = s.released_by
			WHERE s.report_id = :reportId
			ORDER BY s.created_at DESC, s.sanction_id DESC
			""";
		return jdbcTemplate.query(sql, Map.of("reportId", reportId), (rs, rowNumber) -> new AdminReportSanctionRow(
			rs.getLong("sanction_id"),
			rs.getString("decision_source"),
			rs.getString("sanction_type"),
			rs.getString("reason"),
			longObject(rs.getObject("admin_id")),
			rs.getString("admin_nickname"),
			rs.getObject("starts_at", OffsetDateTime.class),
			rs.getObject("ends_at", OffsetDateTime.class),
			rs.getObject("released_at", OffsetDateTime.class),
			longObject(rs.getObject("released_by")),
			rs.getString("released_by_nickname"),
			rs.getObject("created_at", OffsetDateTime.class)
		));
	}

	public Optional<AdminReportDecisionTargetRow> findDecisionTarget(Long reportId) {
		String sql = "SELECT reported_user_id FROM reports WHERE report_id = :reportId";
		return jdbcTemplate.query(sql, Map.of("reportId", reportId), (rs, rowNumber) ->
			new AdminReportDecisionTargetRow(longObject(rs.getObject("reported_user_id")))
		).stream().findFirst();
	}

	public boolean lockUserForDecision(Long userId) {
		String sql = "SELECT user_id FROM users WHERE user_id = :userId FOR UPDATE";
		return !jdbcTemplate.queryForList(sql, Map.of("userId", userId), Long.class).isEmpty();
	}

	public Optional<AdminReportLockedRow> lockReportForDecision(Long reportId) {
		String sql = """
			SELECT reported_user_id, CAST(status AS varchar) AS status, resolved_by, resolved_at
			FROM reports
			WHERE report_id = :reportId
			FOR UPDATE
			""";
		return jdbcTemplate.query(sql, Map.of("reportId", reportId), (rs, rowNumber) -> new AdminReportLockedRow(
			longObject(rs.getObject("reported_user_id")),
			rs.getString("status"),
			longObject(rs.getObject("resolved_by")),
			rs.getObject("resolved_at", OffsetDateTime.class)
		)).stream().findFirst();
	}

	public int resolveReport(Long reportId, String status, Long adminId, OffsetDateTime resolvedAt) {
		String sql = """
			UPDATE reports
			SET status = CAST(:status AS report_status),
			    resolved_by = :adminId,
			    resolved_at = :resolvedAt,
			    ai_review_state = CASE
			        WHEN ai_review_state IN ('pending', 'processing', 'retry') THEN 'cancelled'::ai_job_status
			        ELSE ai_review_state
			    END,
			    ai_review_attempt_id = CASE
			        WHEN ai_review_state IN ('pending', 'processing', 'retry') THEN NULL
			        ELSE ai_review_attempt_id
			    END,
			    ai_next_attempt_at = CASE
			        WHEN ai_review_state IN ('pending', 'processing', 'retry') THEN NULL
			        ELSE ai_next_attempt_at
			    END,
			    ai_lease_until = CASE
			        WHEN ai_review_state IN ('pending', 'processing', 'retry') THEN NULL
			        ELSE ai_lease_until
			    END,
			    ai_locked_by = CASE
			        WHEN ai_review_state IN ('pending', 'processing', 'retry') THEN NULL
			        ELSE ai_locked_by
			    END
			WHERE report_id = :reportId
			  AND status IN ('pending', 'ai_reviewed')
			""";
		MapSqlParameterSource params = new MapSqlParameterSource()
			.addValue("reportId", reportId, Types.BIGINT)
			.addValue("status", status, Types.VARCHAR)
			.addValue("adminId", adminId, Types.BIGINT)
			.addValue("resolvedAt", resolvedAt, Types.TIMESTAMP_WITH_TIMEZONE);
		return jdbcTemplate.update(sql, params);
	}

	public int cancelActiveAiWork(Long reportId) {
		String sql = """
			UPDATE reports
			SET ai_review_state = 'cancelled',
			    ai_review_attempt_id = NULL,
			    ai_next_attempt_at = NULL,
			    ai_lease_until = NULL,
			    ai_locked_by = NULL
			WHERE report_id = :reportId
			  AND ai_review_state IN ('pending', 'processing', 'retry')
			""";
		return jdbcTemplate.update(sql, Map.of("reportId", reportId));
	}

	public int releaseLinkedAiSanctions(
		Long reportId,
		Long userId,
		Long releasedBy,
		OffsetDateTime releasedAt
	) {
		String sql = """
			WITH target AS (
			    SELECT sanction_id, starts_at, ends_at,
			           CASE
			               WHEN ends_at <= :releasedAt THEN INTERVAL '0'
			               WHEN starts_at > :releasedAt THEN ends_at - starts_at
			               ELSE ends_at - :releasedAt
			           END AS remaining_duration
			    FROM user_sanctions
			    WHERE report_id = :reportId
			      AND user_id = :userId
			      AND decision_source = 'ai_recommendation'
			      AND sanction_type = 'temporary'
			      AND revoked_at IS NULL
			    FOR UPDATE
			),
			dismissed AS (
			    UPDATE user_sanctions sanction
			    SET released_at = :releasedAt,
			        released_by = :releasedBy,
			        revoked_at = :releasedAt,
			        revoked_by = :releasedBy,
			        review_status = 'dismissed'
			    FROM target
			    WHERE sanction.sanction_id = target.sanction_id
			    RETURNING target.sanction_id, target.ends_at, target.remaining_duration
			),
			shifted AS (
			    UPDATE user_sanctions queued
			    SET starts_at = queued.starts_at - dismissed.remaining_duration,
			        ends_at = queued.ends_at - dismissed.remaining_duration
			    FROM dismissed
			    WHERE queued.user_id = :userId
			      AND queued.sanction_id <> dismissed.sanction_id
			      AND queued.sanction_type = 'temporary'
			      AND queued.revoked_at IS NULL
			      AND queued.review_status <> 'dismissed'
			      AND queued.starts_at >= dismissed.ends_at
			    RETURNING queued.sanction_id
			)
			SELECT COUNT(*) FROM dismissed
			""";
		MapSqlParameterSource params = new MapSqlParameterSource()
			.addValue("reportId", reportId, Types.BIGINT)
			.addValue("userId", userId, Types.BIGINT)
			.addValue("releasedBy", releasedBy, Types.BIGINT)
			.addValue("releasedAt", releasedAt, Types.TIMESTAMP_WITH_TIMEZONE);
		Long dismissed = jdbcTemplate.queryForObject(sql, params, Long.class);
		return dismissed == null ? 0 : Math.toIntExact(dismissed);
	}

	public boolean hasActiveSanctions(Long userId) {
		Long count = jdbcTemplate.queryForObject(
			"""
				SELECT COUNT(*)
				FROM user_sanctions
				WHERE user_id = :userId
				  AND revoked_at IS NULL
				  AND review_status <> 'dismissed'
				  AND (
				      sanction_type = 'permanent'
				      OR ends_at > CURRENT_TIMESTAMP
				  )
				""",
			Map.of("userId", userId),
			Long.class
		);
		return count != null && count > 0;
	}

	public int activateUser(Long userId) {
		return jdbcTemplate.update(
			"""
				UPDATE users
				SET status = 'active',
				    auth_version = auth_version + 1
				WHERE user_id = :userId
				  AND status = 'suspended'
				""",
			Map.of("userId", userId)
		);
	}

	private static Long longObject(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	public record AdminReportListRow(
		Long reportId,
		String targetType,
		Long targetId,
		boolean targetDeleted,
		Long reporterId,
		String reporterNickname,
		Long reportedUserId,
		String reportedUserNickname,
		String reason,
		String status,
		String aiReviewState,
		String aiRecommendation,
		String aiDecision,
		BigDecimal aiConfidence,
		OffsetDateTime aiReviewedAt,
		OffsetDateTime createdAt
	) {
	}

	public record AdminReportDetailRow(
		Long reportId,
		String targetType,
		Long targetId,
		boolean targetDeleted,
		Long reporterId,
		String reporterNickname,
		Long reportedUserId,
		String reportedUserNickname,
		String reason,
		String detail,
		String status,
		String contextSnapshot,
		String contextHash,
		String aiReviewState,
		String aiRecommendation,
		String aiReason,
		BigDecimal aiConfidence,
		String aiModelVersion,
		String aiPolicyVersion,
		OffsetDateTime aiReviewedAt,
		String aiDecision,
		String aiPolicySetHash,
		String aiReviewResult,
		String aiLastErrorCode,
		Long resolvedById,
		String resolvedByNickname,
		OffsetDateTime resolvedAt,
		OffsetDateTime createdAt
	) {
	}

	public record AdminReportSanctionRow(
		Long sanctionId,
		String decisionSource,
		String sanctionType,
		String reason,
		Long adminId,
		String adminNickname,
		OffsetDateTime startsAt,
		OffsetDateTime endsAt,
		OffsetDateTime releasedAt,
		Long releasedById,
		String releasedByNickname,
		OffsetDateTime createdAt
	) {
	}

	public record AdminReportDecisionTargetRow(Long reportedUserId) {
	}

	public record AdminReportLockedRow(
		Long reportedUserId,
		String status,
		Long resolvedBy,
		OffsetDateTime resolvedAt
	) {
	}
}
