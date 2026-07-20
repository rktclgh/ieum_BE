package shinhan.fibri.ieum.main.admin.knowledge.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import shinhan.fibri.ieum.common.knowledge.KnowledgeRelationPredicate;

@Repository
@RequiredArgsConstructor
public class JdbcKnowledgeGraphAdminRepository {

	private final JdbcClient jdbc;

	public List<GraphRelationRow> findGraphRelations(
		String query,
		String focus,
		KnowledgeRelationPredicate predicate,
		int limit
	) {
		StringBuilder sql = new StringBuilder("""
			SELECT rel.relation_id, rel.source_id, rel.subject, rel.predicate, rel.object, rel.confidence,
			       rel.evidence_chunk_id, rel.created_at, ks.display_name, kc.content AS evidence_preview
			FROM knowledge_relations rel
			JOIN knowledge_sources ks ON ks.source_id = rel.source_id
			LEFT JOIN questions q ON q.question_id = ks.question_id
			LEFT JOIN pins p ON p.pin_id = q.pin_id
			LEFT JOIN answers a ON a.answer_id = ks.answer_id AND a.question_id = q.question_id
			LEFT JOIN knowledge_chunks kc
			       ON kc.source_id = rel.source_id AND kc.chunk_id = rel.evidence_chunk_id
			WHERE ks.status = 'ready'
			  AND ks.active
			  AND (ks.valid_until IS NULL OR ks.valid_until > clock_timestamp())
			  AND (
			      ks.source_type <> 'accepted_human_answer'
			      OR (
			          q.deleted_at IS NULL
			          AND p.deleted_at IS NULL
			          AND p.pin_type = 'question'
			          AND a.is_accepted
			          AND NOT a.is_ai
			          AND a.author_id IS NOT NULL
			          AND btrim(a.content) <> ''
			      )
			  )
			""");
		if (query != null) {
			sql.append("  AND (rel.subject LIKE :query ESCAPE '\\' OR rel.object LIKE :query ESCAPE '\\')\n");
		}
		if (focus != null) {
			sql.append("  AND (rel.subject = :focus OR rel.object = :focus)\n");
		}
		if (predicate != null) {
			sql.append("  AND rel.predicate = :predicate\n");
		}
		sql.append("""
			ORDER BY rel.relation_id DESC
			LIMIT :limit
			""");

		var statement = jdbc.sql(sql.toString())
			.param("limit", limit);
		if (query != null) {
			statement = statement.param("query", likePattern(query));
		}
		if (focus != null) {
			statement = statement.param("focus", focus);
		}
		if (predicate != null) {
			statement = statement.param("predicate", predicate.name());
		}
		return statement.query(this::mapGraphRelation).list();
	}

	private String likePattern(String value) {
		String escaped = value
			.replace("\\", "\\\\")
			.replace("%", "\\%")
			.replace("_", "\\_");
		return "%" + escaped + "%";
	}

	private GraphRelationRow mapGraphRelation(ResultSet rs, int rowNum) throws SQLException {
		return new GraphRelationRow(
			rs.getLong("relation_id"),
			rs.getLong("source_id"),
			rs.getString("subject"),
			KnowledgeRelationPredicate.valueOf(rs.getString("predicate")),
			rs.getString("object"),
			rs.getBigDecimal("confidence"),
			nullableLong(rs, "evidence_chunk_id"),
			rs.getString("display_name"),
			rs.getString("evidence_preview"),
			rs.getObject("created_at", OffsetDateTime.class)
		);
	}

	private Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	public record GraphRelationRow(
		Long relationId,
		Long sourceId,
		String subject,
		KnowledgeRelationPredicate predicate,
		String object,
		BigDecimal confidence,
		Long evidenceChunkId,
		String sourceDisplayName,
		String evidencePreview,
		OffsetDateTime createdAt
	) {
	}
}
