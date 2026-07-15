package shinhan.fibri.ieum.main.admin.user.repository;

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
import shinhan.fibri.ieum.common.auth.domain.UserRole;

@Repository
@RequiredArgsConstructor
public class JdbcAdminUserHardDeleteRepository implements AdminUserHardDeleteRepository {

	private static final Logger log = LoggerFactory.getLogger(JdbcAdminUserHardDeleteRepository.class);
	private static final int FILE_DELETE_BATCH_SIZE = 1_000;

	private final NamedParameterJdbcTemplate jdbc;

	@Override
	public Optional<HardDeleteTarget> findForHardDelete(Long userId) {
		List<HardDeleteTarget> targets = jdbc.query(
			"""
				SELECT user_id, email, role::text AS role
				  FROM users
				 WHERE user_id = :userId
				""",
			new MapSqlParameterSource("userId", userId),
			this::toTarget
		);
		return targets.stream().findFirst();
	}

	@Override
	public boolean isReferencedAsActor(Long userId) {
		Boolean referenced = jdbc.queryForObject(
			"""
				SELECT EXISTS (
					SELECT 1
					  FROM user_sanctions
					 WHERE user_id <> :userId
					   AND (admin_id = :userId OR revoked_by = :userId OR released_by = :userId)
					UNION ALL
					SELECT 1
					  FROM reports
					 WHERE resolved_by = :userId
					   AND reporter_id <> :userId
					   AND reported_user_id IS DISTINCT FROM :userId
				)
				""",
			new MapSqlParameterSource("userId", userId),
			Boolean.class
		);
		return Boolean.TRUE.equals(referenced);
	}

	@Override
	@Transactional
	public List<String> hardDelete(Long userId) {
		List<FileRow> files = selectFilesForHardDelete(userId);
		logCollectedKeys(userId, files);

		MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
		int resetQuestions = resetQuestionsResolvedByAcceptedAnswers(userId);
		int userDeletes = jdbc.update("DELETE FROM users WHERE user_id = :userId", params);
		int fileDeletes = deleteFiles(files);
		log.info(
			"Admin user hard delete DB deletes completed. userId={}, users={}, files={}, resetQuestions={}",
			userId,
			userDeletes,
			fileDeletes,
			resetQuestions
		);
		return files.stream().map(FileRow::s3Key).toList();
	}

	private int resetQuestionsResolvedByAcceptedAnswers(Long userId) {
		return jdbc.update(
			"""
				UPDATE questions q
				   SET is_resolved = false,
				       updated_at = now()
				 WHERE q.author_id <> :userId
				   AND q.is_resolved = true
				   AND EXISTS (
				   	SELECT 1
				   	  FROM answers a
				   	 WHERE a.question_id = q.question_id
				   	   AND a.author_id = :userId
				   	   AND a.is_accepted = true
				   )
				""",
			new MapSqlParameterSource("userId", userId)
		);
	}

	private List<FileRow> selectFilesForHardDelete(Long userId) {
		return jdbc.query(
			"""
				WITH target_questions AS (
					SELECT question_id
					  FROM questions
					 WHERE author_id = :userId
				),
				target_meetings AS (
					SELECT meeting_id, image_file_id, thumbnail_file_id
					  FROM meetings
					 WHERE host_id = :userId
				),
				target_rooms AS (
					SELECT room_id
					  FROM chat_rooms
					 WHERE question_id IN (SELECT question_id FROM target_questions)
					UNION
					SELECT room_id
					  FROM chat_rooms
					 WHERE meeting_id IN (SELECT meeting_id FROM target_meetings)
				),
				target_answers AS (
					SELECT answer_id
					  FROM answers
					 WHERE author_id = :userId
					UNION
					SELECT answer_id
					  FROM answers
					 WHERE question_id IN (SELECT question_id FROM target_questions)
				),
				file_refs AS (
					SELECT file_id
					  FROM files
					 WHERE uploader_id = :userId
					UNION
					SELECT profile_file_id AS file_id
					  FROM users
					 WHERE user_id = :userId
					   AND profile_file_id IS NOT NULL
					UNION
					SELECT file_id
					  FROM question_images
					 WHERE question_id IN (SELECT question_id FROM target_questions)
					UNION
					SELECT file_id
					  FROM answer_images
					 WHERE answer_id IN (SELECT answer_id FROM target_answers)
					UNION
					SELECT image_file_id AS file_id
					  FROM target_meetings
					 WHERE image_file_id IS NOT NULL
					UNION
					SELECT thumbnail_file_id AS file_id
					  FROM target_meetings
					 WHERE thumbnail_file_id IS NOT NULL
					UNION
					SELECT image_file_id AS file_id
					  FROM messages
					 WHERE sender_id = :userId
					   AND image_file_id IS NOT NULL
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
			new MapSqlParameterSource("userId", userId),
			this::toFileRow
		);
	}

	private int deleteFiles(List<FileRow> files) {
		List<UUID> fileIds = files.stream().map(FileRow::fileId).toList();
		if (fileIds.isEmpty()) {
			return 0;
		}
		int deleted = 0;
		for (int from = 0; from < fileIds.size(); from += FILE_DELETE_BATCH_SIZE) {
			int to = Math.min(from + FILE_DELETE_BATCH_SIZE, fileIds.size());
			deleted += jdbc.update(
				"DELETE FROM files WHERE file_id IN (:fileIds)",
				new MapSqlParameterSource("fileIds", fileIds.subList(from, to))
			);
		}
		return deleted;
	}

	private HardDeleteTarget toTarget(ResultSet rs, int rowNum) throws SQLException {
		return new HardDeleteTarget(
			rs.getLong("user_id"),
			rs.getString("email"),
			UserRole.valueOf(rs.getString("role"))
		);
	}

	private FileRow toFileRow(ResultSet rs, int rowNum) throws SQLException {
		return new FileRow((UUID) rs.getObject("file_id"), rs.getString("s3_key"));
	}

	private void logCollectedKeys(Long userId, List<FileRow> files) {
		if (files.isEmpty()) {
			log.info("Admin user hard delete collected no S3 keys before DB delete. userId={}", userId);
			return;
		}
		log.info(
			"Admin user hard delete collected S3 keys before DB delete. userId={}, s3Keys={}",
			userId,
			files.stream().map(FileRow::s3Key).toList()
		);
	}

	private record FileRow(UUID fileId, String s3Key) {
	}
}
