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
		List<FileRow> files = selectUploadedFiles(userId);
		logCollectedKeys(userId, files);

		MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
		int userDeletes = jdbc.update("DELETE FROM users WHERE user_id = :userId", params);
		int fileDeletes = deleteFiles(files);
		log.info(
			"Admin user hard delete DB deletes completed. userId={}, users={}, files={}",
			userId,
			userDeletes,
			fileDeletes
		);
		return files.stream().map(FileRow::s3Key).toList();
	}

	private List<FileRow> selectUploadedFiles(Long userId) {
		return jdbc.query(
			"""
				SELECT file_id, s3_key
				  FROM files
				 WHERE uploader_id = :userId
				 ORDER BY file_id
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
		return jdbc.update(
			"DELETE FROM files WHERE file_id IN (:fileIds)",
			new MapSqlParameterSource("fileIds", fileIds)
		);
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
