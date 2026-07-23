package shinhan.fibri.ieum.main.admin.content.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import shinhan.fibri.ieum.main.admin.content.domain.AdminContentType;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentDetailResponse;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentListItem;

@Repository
@RequiredArgsConstructor
public class JdbcAdminContentQueryRepository implements AdminContentQueryRepository {

	private final NamedParameterJdbcTemplate jdbc;

	@Override
	public List<AdminContentListItem> findQuestions(Long cursorId, int limit) {
		return jdbc.query(
			"""
				SELECT 'question' AS content_type,
				       q.question_id AS content_id,
				       q.title,
				       u.nickname AS author_nickname,
				       q.author_id,
				       q.created_at,
				       q.updated_at,
				       q.deleted_at,
				       q.is_resolved AS resolved,
				       CAST(NULL AS text) AS status,
				       CAST(NULL AS integer) AS participant_count
				  FROM questions q
				  JOIN users u ON u.user_id = q.author_id
				 WHERE q.deleted_at IS NULL
				   AND (:cursorId IS NULL OR q.question_id < :cursorId)
				 ORDER BY q.question_id DESC
				 LIMIT :limit
			""",
			new MapSqlParameterSource()
				.addValue("cursorId", cursorId, Types.BIGINT)
				.addValue("limit", limit),
			this::toListItem
		);
	}

	@Override
	public List<AdminContentListItem> findMeetings(Long cursorId, int limit) {
		return jdbc.query(
			"""
				SELECT 'meeting' AS content_type,
				       m.meeting_id AS content_id,
				       m.title,
				       u.nickname AS author_nickname,
				       m.host_id AS author_id,
				       m.created_at,
				       m.updated_at,
				       m.deleted_at,
				       CAST(NULL AS boolean) AS resolved,
				       CAST(m.status AS text) AS status,
				       CAST(COUNT(mp.user_id) AS integer) AS participant_count
				  FROM meetings m
				  JOIN users u ON u.user_id = m.host_id
				  LEFT JOIN meeting_participants mp
				    ON mp.meeting_id = m.meeting_id
				   AND mp.status = 'joined'
				 WHERE m.deleted_at IS NULL
				   AND (:cursorId IS NULL OR m.meeting_id < :cursorId)
				 GROUP BY m.meeting_id, u.nickname
				 ORDER BY m.meeting_id DESC
				 LIMIT :limit
			""",
			new MapSqlParameterSource()
				.addValue("cursorId", cursorId, Types.BIGINT)
				.addValue("limit", limit),
			this::toListItem
		);
	}

	@Override
	public Optional<AdminContentDetailResponse> findDetail(AdminContentType type, Long id) {
		return selectDetail(type, id, false);
	}

	@Override
	public Optional<AdminContentDetailResponse> lockDetail(AdminContentType type, Long id) {
		return selectDetail(type, id, true);
	}

	@Override
	public void update(AdminContentType type, Long id, String title, String content) {
		String sql = switch (type) {
			case QUESTION -> """
				UPDATE questions
				   SET title = :title,
				       content = :content,
				       updated_at = CURRENT_TIMESTAMP
				 WHERE question_id = :id
				   AND deleted_at IS NULL
				""";
			case MEETING -> """
				UPDATE meetings
				   SET title = :title,
				       content = :content,
				       updated_at = CURRENT_TIMESTAMP
				 WHERE meeting_id = :id
				   AND deleted_at IS NULL
				""";
		};
		jdbc.update(
			sql,
			new MapSqlParameterSource()
				.addValue("id", id)
				.addValue("title", title)
				.addValue("content", content)
		);
	}

	private Optional<AdminContentDetailResponse> selectDetail(AdminContentType type, Long id, boolean lock) {
		String sql = switch (type) {
			case QUESTION -> """
				SELECT 'question' AS content_type,
				       q.question_id AS content_id,
				       q.title,
				       q.content,
				       u.nickname AS author_nickname,
				       q.author_id,
				       q.created_at,
				       q.updated_at,
				       q.deleted_at,
				       q.is_resolved AS resolved,
				       CAST(NULL AS text) AS status,
				       CAST(NULL AS integer) AS participant_count
				  FROM questions q
				  JOIN users u ON u.user_id = q.author_id
				 WHERE q.question_id = :id
				   AND q.deleted_at IS NULL
			""" + (lock ? " FOR UPDATE OF q" : "");
			case MEETING -> """
				SELECT 'meeting' AS content_type,
				       m.meeting_id AS content_id,
				       m.title,
				       m.content,
				       u.nickname AS author_nickname,
				       m.host_id AS author_id,
				       m.created_at,
				       m.updated_at,
				       m.deleted_at,
				       CAST(NULL AS boolean) AS resolved,
				       CAST(m.status AS text) AS status,
				       participants.participant_count
				  FROM meetings m
				  JOIN users u ON u.user_id = m.host_id
				  LEFT JOIN LATERAL (
				      SELECT CAST(COUNT(*) AS integer) AS participant_count
				        FROM meeting_participants mp
				       WHERE mp.meeting_id = m.meeting_id
				         AND mp.status = 'joined'
				  ) participants ON true
				 WHERE m.meeting_id = :id
				   AND m.deleted_at IS NULL
			""" + (lock ? " FOR UPDATE OF m" : "");
		};
		List<AdminContentDetailResponse> rows = jdbc.query(
			sql,
			new MapSqlParameterSource("id", id),
			this::toDetail
		);
		return rows.stream().findFirst();
	}

	private AdminContentListItem toListItem(ResultSet rs, int rowNum) throws SQLException {
		return new AdminContentListItem(
			rs.getString("content_type"),
			rs.getLong("content_id"),
			rs.getString("title"),
			rs.getString("author_nickname"),
			rs.getLong("author_id"),
			rs.getObject("created_at", OffsetDateTime.class),
			rs.getObject("updated_at", OffsetDateTime.class),
			rs.getObject("deleted_at", OffsetDateTime.class),
			booleanObject(rs, "resolved"),
			rs.getString("status"),
			integerObject(rs, "participant_count")
		);
	}

	private AdminContentDetailResponse toDetail(ResultSet rs, int rowNum) throws SQLException {
		return new AdminContentDetailResponse(
			rs.getString("content_type"),
			rs.getLong("content_id"),
			rs.getString("title"),
			rs.getString("content"),
			rs.getString("author_nickname"),
			rs.getLong("author_id"),
			rs.getObject("created_at", OffsetDateTime.class),
			rs.getObject("updated_at", OffsetDateTime.class),
			rs.getObject("deleted_at", OffsetDateTime.class),
			booleanObject(rs, "resolved"),
			rs.getString("status"),
			integerObject(rs, "participant_count")
		);
	}

	private static Boolean booleanObject(ResultSet rs, String column) throws SQLException {
		boolean value = rs.getBoolean(column);
		return rs.wasNull() ? null : value;
	}

	private static Integer integerObject(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}
}
