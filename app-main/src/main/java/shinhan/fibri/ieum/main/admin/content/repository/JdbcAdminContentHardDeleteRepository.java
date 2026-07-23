package shinhan.fibri.ieum.main.admin.content.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.main.admin.content.domain.AdminContentType;

@Repository
@RequiredArgsConstructor
public class JdbcAdminContentHardDeleteRepository implements AdminContentHardDeleteRepository {

	private static final Logger log = LoggerFactory.getLogger(JdbcAdminContentHardDeleteRepository.class);
	private static final int FILE_DELETE_BATCH_SIZE = 1_000;

	private final NamedParameterJdbcTemplate jdbc;

	@Override
	public Optional<AdminContentHardDeleteTarget> preview(AdminContentType type, Long id) {
		return selectTarget(type, id, false);
	}

	@Override
	public Optional<AdminContentHardDeleteTarget> findForHardDelete(AdminContentType type, Long id) {
		return selectTarget(type, id, true);
	}

	@Override
	@Transactional
	public AdminContentHardDeleteResult hardDelete(AdminContentType type, AdminContentHardDeleteTarget target) {
		List<FileRow> candidates = selectCandidateFiles(type, target.contentId());
		logCollectedKeys(type, target.contentId(), candidates);

		MapSqlParameterSource params = new MapSqlParameterSource()
			.addValue("id", target.contentId())
			.addValue("pinId", target.pinId())
			.addValue("type", type.pathValue());
		int notificationDeletes = jdbc.update(
			"DELETE FROM notifications WHERE type = CAST(:type AS notification_type) AND ref_id = :id",
			params
		);
		int contentDeletes = switch (type) {
			case QUESTION -> jdbc.update("DELETE FROM questions WHERE question_id = :id", params);
			case MEETING -> jdbc.update("DELETE FROM meetings WHERE meeting_id = :id", params);
		};
		int pinDeletes = jdbc.update("DELETE FROM pins WHERE pin_id = :pinId", params);
		List<String> deletedS3Keys = deleteUnreferencedFiles(candidates);
		log.info(
			"Admin content hard delete DB deletes completed. type={}, id={}, contentRows={}, pins={}, notifications={}, files={}",
			type.pathValue(),
			target.contentId(),
			contentDeletes,
			pinDeletes,
			notificationDeletes,
			deletedS3Keys.size()
		);
		return new AdminContentHardDeleteResult(deletedS3Keys);
	}

	private Optional<AdminContentHardDeleteTarget> selectTarget(AdminContentType type, Long id, boolean lock) {
		String sql = switch (type) {
			case QUESTION -> """
				SELECT q.question_id AS content_id,
				       q.pin_id,
				       q.title,
				       u.nickname AS author_nickname,
				       q.author_id,
				       q.created_at,
				       q.deleted_at
				  FROM questions q
				  JOIN users u ON u.user_id = q.author_id
				 WHERE q.question_id = :id
				""" + (lock ? " FOR UPDATE OF q" : "");
			case MEETING -> """
				SELECT m.meeting_id AS content_id,
				       m.pin_id,
				       m.title,
				       u.nickname AS author_nickname,
				       m.host_id AS author_id,
				       m.created_at,
				       m.deleted_at
				  FROM meetings m
				  JOIN users u ON u.user_id = m.host_id
				 WHERE m.meeting_id = :id
				""" + (lock ? " FOR UPDATE OF m" : "");
		};
		List<AdminContentHardDeleteTarget> targets = jdbc.query(
			sql,
			new MapSqlParameterSource("id", id),
			(rs, rowNum) -> toTarget(type, rs)
		);
		return targets.stream().findFirst();
	}

	private List<FileRow> selectCandidateFiles(AdminContentType type, Long id) {
		return switch (type) {
			case QUESTION -> selectQuestionCandidateFiles(id);
			case MEETING -> selectMeetingCandidateFiles(id);
		};
	}

	private List<FileRow> selectQuestionCandidateFiles(Long questionId) {
		return jdbc.query(
			"""
				WITH target_answers AS (
					SELECT answer_id
					  FROM answers
					 WHERE question_id = :id
				),
				target_rooms AS (
					SELECT room_id
					  FROM chat_rooms
					 WHERE question_id = :id
				),
				file_refs AS (
					SELECT file_id
					  FROM question_images
					 WHERE question_id = :id
					UNION
					SELECT file_id
					  FROM answer_images
					 WHERE answer_id IN (SELECT answer_id FROM target_answers)
					UNION
					SELECT image_file_id AS file_id
					  FROM messages
					 WHERE room_id IN (SELECT room_id FROM target_rooms)
					   AND image_file_id IS NOT NULL
				)
				SELECT DISTINCT f.file_id, f.s3_key
				  FROM files f
				  JOIN file_refs fr ON fr.file_id = f.file_id
				 ORDER BY f.file_id
				""",
			new MapSqlParameterSource("id", questionId),
			this::toFileRow
		);
	}

	private List<FileRow> selectMeetingCandidateFiles(Long meetingId) {
		return jdbc.query(
			"""
				WITH target_meeting AS (
					SELECT meeting_id, image_file_id, thumbnail_file_id
					  FROM meetings
					 WHERE meeting_id = :id
				),
				target_rooms AS (
					SELECT room_id
					  FROM chat_rooms
					 WHERE meeting_id = :id
				),
				file_refs AS (
					SELECT image_file_id AS file_id
					  FROM target_meeting
					 WHERE image_file_id IS NOT NULL
					UNION
					SELECT thumbnail_file_id AS file_id
					  FROM target_meeting
					 WHERE thumbnail_file_id IS NOT NULL
					UNION
					SELECT image_file_id AS file_id
					  FROM messages
					 WHERE room_id IN (SELECT room_id FROM target_rooms)
					   AND image_file_id IS NOT NULL
				)
				SELECT DISTINCT f.file_id, f.s3_key
				  FROM files f
				  JOIN file_refs fr ON fr.file_id = f.file_id
				 ORDER BY f.file_id
				""",
			new MapSqlParameterSource("id", meetingId),
			this::toFileRow
		);
	}

	private List<String> deleteUnreferencedFiles(List<FileRow> files) {
		if (files.isEmpty()) {
			return List.of();
		}
		List<String> deletedS3Keys = new java.util.ArrayList<>();
		List<UUID> fileIds = files.stream().map(FileRow::fileId).toList();
		for (int from = 0; from < fileIds.size(); from += FILE_DELETE_BATCH_SIZE) {
			int to = Math.min(from + FILE_DELETE_BATCH_SIZE, fileIds.size());
			deletedS3Keys.addAll(jdbc.query(
				"""
					DELETE FROM files f
					 WHERE f.file_id IN (:fileIds)
					   AND NOT EXISTS (SELECT 1 FROM question_images qi WHERE qi.file_id = f.file_id)
					   AND NOT EXISTS (SELECT 1 FROM answer_images ai WHERE ai.file_id = f.file_id)
					   AND NOT EXISTS (
					    SELECT 1
					      FROM meetings m
					     WHERE m.image_file_id = f.file_id
					        OR m.thumbnail_file_id = f.file_id
					   )
					   AND NOT EXISTS (SELECT 1 FROM messages msg WHERE msg.image_file_id = f.file_id)
					   AND NOT EXISTS (SELECT 1 FROM users u WHERE u.profile_file_id = f.file_id)
					 RETURNING f.s3_key
					""",
				new MapSqlParameterSource("fileIds", fileIds.subList(from, to)),
				(rs, rowNum) -> rs.getString("s3_key")
			));
		}
		return deletedS3Keys;
	}

	private AdminContentHardDeleteTarget toTarget(AdminContentType type, ResultSet rs) throws SQLException {
		return new AdminContentHardDeleteTarget(
			type,
			rs.getLong("content_id"),
			rs.getLong("pin_id"),
			rs.getString("title"),
			rs.getString("author_nickname"),
			rs.getLong("author_id"),
			rs.getObject("created_at", java.time.OffsetDateTime.class),
			rs.getObject("deleted_at", java.time.OffsetDateTime.class)
		);
	}

	private FileRow toFileRow(ResultSet rs, int rowNum) throws SQLException {
		return new FileRow((UUID) rs.getObject("file_id"), rs.getString("s3_key"));
	}

	private void logCollectedKeys(AdminContentType type, Long id, List<FileRow> files) {
		if (files.isEmpty()) {
			log.info("Admin content hard delete collected no S3 keys before DB delete. type={}, id={}", type.pathValue(), id);
			return;
		}
		log.info(
			"Admin content hard delete collected S3 keys before DB delete. type={}, id={}, s3KeyCount={}",
			type.pathValue(),
			id,
			files.size()
		);
	}

	private record FileRow(UUID fileId, String s3Key) {
	}
}
