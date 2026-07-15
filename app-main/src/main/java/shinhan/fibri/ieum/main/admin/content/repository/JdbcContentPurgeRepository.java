package shinhan.fibri.ieum.main.admin.content.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JdbcContentPurgeRepository implements ContentPurgeRepository {

	private static final Logger log = LoggerFactory.getLogger(JdbcContentPurgeRepository.class);

	private final NamedParameterJdbcTemplate jdbc;

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ContentPurgeChunk purgeChunk(OffsetDateTime cutoff, int limit) {
		List<TargetRow> targets = selectTargets(cutoff, limit);
		if (targets.isEmpty()) {
			return ContentPurgeChunk.empty();
		}

		List<Long> questionIds = targets.stream().map(TargetRow::questionId).toList();
		List<Long> pinIds = targets.stream().map(TargetRow::pinId).toList();
		MapSqlParameterSource params = new MapSqlParameterSource("questionIds", questionIds);
		List<FileRow> files = selectFiles(params);
		logPurgeKeys(questionIds, files);

		int answerImageDeletes = deleteByQuestionIds("""
			DELETE FROM answer_images
			 WHERE answer_id IN (
			       SELECT answer_id
			         FROM answers
			        WHERE question_id IN (:questionIds)
			 )
			""", params);
		int questionImageDeletes = deleteByQuestionIds("DELETE FROM question_images WHERE question_id IN (:questionIds)", params);
		KnowledgeDeleteCounts knowledgeDeletes = deleteKnowledgeRows(params);
		int aiTaskDeletes = deleteByQuestionIds("DELETE FROM ai_question_tasks WHERE question_id IN (:questionIds)", params);
		int answerDeletes = deleteByQuestionIds("DELETE FROM answers WHERE question_id IN (:questionIds)", params);
		int questionDeletes = deleteByQuestionIds("DELETE FROM questions WHERE question_id IN (:questionIds)", params);
		int pinDeletes = deleteByIds("DELETE FROM pins WHERE pin_id IN (:ids)", pinIds);
		int fileDeletes = deleteFiles(files);
		log.info(
			"Content purge DB deletes completed. questions={}, pins={}, answers={}, answerImages={}, questionImages={}, knowledgeSources={}, knowledgeChunks={}, knowledgeRelations={}, aiTasks={}, files={}",
			questionDeletes,
			pinDeletes,
			answerDeletes,
			answerImageDeletes,
			questionImageDeletes,
			knowledgeDeletes.sources(),
			knowledgeDeletes.chunks(),
			knowledgeDeletes.relations(),
			aiTaskDeletes,
			fileDeletes
		);

		return new ContentPurgeChunk(targets.size(), files.stream().map(FileRow::s3Key).toList());
	}

	private List<TargetRow> selectTargets(OffsetDateTime cutoff, int limit) {
		return jdbc.query(
			"""
				SELECT q.question_id, q.pin_id
				  FROM questions q
				 WHERE q.deleted_at < :cutoff
				 ORDER BY q.deleted_at ASC, q.question_id ASC
				 LIMIT :limit
				 FOR UPDATE SKIP LOCKED
				""",
			new MapSqlParameterSource()
				.addValue("cutoff", cutoff)
				.addValue("limit", limit),
			(rs, rowNum) -> new TargetRow(rs.getLong("question_id"), rs.getLong("pin_id"))
		);
	}

	private List<FileRow> selectFiles(MapSqlParameterSource params) {
		return jdbc.query(
			"""
				SELECT DISTINCT f.file_id, f.s3_key
				  FROM files f
				  JOIN question_images qi ON qi.file_id = f.file_id
				 WHERE qi.question_id IN (:questionIds)
				UNION
				SELECT DISTINCT f.file_id, f.s3_key
				  FROM files f
				  JOIN answer_images ai ON ai.file_id = f.file_id
				  JOIN answers a ON a.answer_id = ai.answer_id
				 WHERE a.question_id IN (:questionIds)
				UNION
				SELECT DISTINCT f.file_id, f.s3_key
				  FROM files f
				  JOIN messages m ON m.image_file_id = f.file_id
				  JOIN chat_rooms cr ON cr.room_id = m.room_id
				 WHERE cr.question_id IN (:questionIds)
				""",
			params,
			this::toFileRow
		);
	}

	private FileRow toFileRow(ResultSet rs, int rowNum) throws SQLException {
		return new FileRow((UUID) rs.getObject("file_id"), rs.getString("s3_key"));
	}

	private void logPurgeKeys(List<Long> questionIds, List<FileRow> files) {
		if (files.isEmpty()) {
			log.info("Content purge collected no S3 keys before DB delete. questionIds={}", questionIds);
			return;
		}
		log.info(
			"Content purge collected S3 keys before DB delete. questionIds={}, s3Keys={}",
			questionIds,
			files.stream().map(FileRow::s3Key).toList()
		);
	}

	private KnowledgeDeleteCounts deleteKnowledgeRows(MapSqlParameterSource params) {
		String sourceFilter = """
			question_id IN (:questionIds)
			OR answer_id IN (
			      SELECT answer_id
			        FROM answers
			       WHERE question_id IN (:questionIds)
			)
			""";
		int relations = jdbc.update("DELETE FROM knowledge_relations WHERE source_id IN (SELECT source_id FROM knowledge_sources WHERE " + sourceFilter + ")", params);
		int chunks = jdbc.update("DELETE FROM knowledge_chunks WHERE source_id IN (SELECT source_id FROM knowledge_sources WHERE " + sourceFilter + ")", params);
		int sources = jdbc.update("DELETE FROM knowledge_sources WHERE " + sourceFilter, params);
		return new KnowledgeDeleteCounts(relations, chunks, sources);
	}

	private int deleteByQuestionIds(String sql, MapSqlParameterSource params) {
		return jdbc.update(sql, params);
	}

	private int deleteByIds(String sql, List<?> ids) {
		if (!ids.isEmpty()) {
			return jdbc.update(sql, new MapSqlParameterSource("ids", ids));
		}
		return 0;
	}

	private int deleteFiles(List<FileRow> files) {
		List<UUID> fileIds = files.stream().map(FileRow::fileId).toList();
		return deleteByIds("DELETE FROM files WHERE file_id IN (:ids)", fileIds);
	}

	private record TargetRow(Long questionId, Long pinId) {
	}

	private record FileRow(UUID fileId, String s3Key) {
	}

	private record KnowledgeDeleteCounts(int relations, int chunks, int sources) {
	}
}
