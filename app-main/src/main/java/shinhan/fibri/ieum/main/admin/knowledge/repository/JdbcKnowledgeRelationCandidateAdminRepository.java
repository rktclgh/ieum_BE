package shinhan.fibri.ieum.main.admin.knowledge.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import shinhan.fibri.ieum.common.knowledge.KnowledgeRelationPredicate;

@Repository
@RequiredArgsConstructor
public class JdbcKnowledgeRelationCandidateAdminRepository {

	private static final String ACTOR = "admin-knowledge";

	private final JdbcClient jdbc;

	public Optional<KnowledgeCandidateRow> findCandidateForUpdate(long candidateId) {
		return jdbc.sql("""
			SELECT candidate_id, source_id, evidence_chunk_id, subject_text, predicate, object_text,
			       confidence, evidence_excerpt, extraction_provider, extraction_model, status, version,
			       reviewer_user_id, reviewed_at, review_note, promotion_relation_id, created_at, updated_at
			FROM knowledge_relation_candidates
			WHERE candidate_id = :candidateId
			FOR UPDATE
			""")
			.param("candidateId", candidateId)
			.query(this::mapCandidate)
			.optional();
	}

	public boolean sourceEligible(long sourceId) {
		return Boolean.TRUE.equals(jdbc.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM knowledge_sources ks
			    JOIN questions q ON q.question_id = ks.question_id
			    JOIN pins p ON p.pin_id = q.pin_id
			    JOIN answers a ON a.answer_id = ks.answer_id AND a.question_id = q.question_id
			    WHERE ks.source_id = :sourceId
			      AND ks.source_type = 'accepted_human_answer'
			      AND ks.status = 'ready'
			      AND ks.active
			      AND (ks.valid_until IS NULL OR ks.valid_until > clock_timestamp())
			      AND q.deleted_at IS NULL
			      AND p.deleted_at IS NULL
			      AND p.pin_type = 'question'
			      AND a.is_accepted
			      AND NOT a.is_ai
			      AND a.author_id IS NOT NULL
			      AND btrim(a.content) <> ''
			)
			""")
			.param("sourceId", sourceId)
			.query(Boolean.class)
			.single());
	}

	public RelationRow upsertRelation(
		long sourceId,
		long evidenceChunkId,
		String subject,
		KnowledgeRelationPredicate predicate,
		String object,
		BigDecimal confidence
	) {
		return jdbc.sql("""
			INSERT INTO knowledge_relations(source_id, subject, predicate, object, confidence, evidence_chunk_id)
			VALUES (:sourceId, :subject, :predicate, :object, :confidence, :evidenceChunkId)
			ON CONFLICT (source_id, subject, predicate, object) DO UPDATE
			SET confidence = knowledge_relations.confidence
			RETURNING relation_id, source_id, subject, predicate, object, confidence, evidence_chunk_id
			""")
			.param("sourceId", sourceId)
			.param("subject", subject)
			.param("predicate", predicate.name())
			.param("object", object)
			.param("confidence", confidence)
			.param("evidenceChunkId", evidenceChunkId)
			.query(this::mapRelation)
			.single();
	}

	public KnowledgeCandidateRow approveCandidate(long candidateId, long actorUserId, RelationRow relation) {
		return jdbc.sql("""
			UPDATE knowledge_relation_candidates
			SET subject_text = :subject,
			    predicate = :predicate,
			    object_text = :object,
			    status = 'approved',
			    version = version + 1,
			    reviewer_user_id = :actorUserId,
			    reviewed_at = clock_timestamp(),
			    review_note = NULL,
			    promotion_relation_id = :relationId,
			    updated_by = :actor
			WHERE candidate_id = :candidateId
			RETURNING candidate_id, source_id, evidence_chunk_id, subject_text, predicate, object_text,
			          confidence, evidence_excerpt, extraction_provider, extraction_model, status, version,
			          reviewer_user_id, reviewed_at, review_note, promotion_relation_id, created_at, updated_at
			""")
			.param("subject", relation.subject())
			.param("predicate", relation.predicate().name())
			.param("object", relation.object())
			.param("actorUserId", actorUserId)
			.param("relationId", relation.relationId())
			.param("actor", ACTOR)
			.param("candidateId", candidateId)
			.query(this::mapCandidate)
			.single();
	}

	public KnowledgeCandidateRow rejectCandidate(long candidateId, long actorUserId, String reason) {
		return jdbc.sql("""
			UPDATE knowledge_relation_candidates
			SET status = 'rejected',
			    version = version + 1,
			    reviewer_user_id = :actorUserId,
			    reviewed_at = clock_timestamp(),
			    review_note = :reason,
			    updated_by = :actor
			WHERE candidate_id = :candidateId
			RETURNING candidate_id, source_id, evidence_chunk_id, subject_text, predicate, object_text,
			          confidence, evidence_excerpt, extraction_provider, extraction_model, status, version,
			          reviewer_user_id, reviewed_at, review_note, promotion_relation_id, created_at, updated_at
			""")
			.param("actorUserId", actorUserId)
			.param("reason", reason)
			.param("actor", ACTOR)
			.param("candidateId", candidateId)
			.query(this::mapCandidate)
			.single();
	}

	public void invalidatePendingCandidate(long candidateId) {
		jdbc.sql("""
			UPDATE knowledge_relation_candidates
			SET status = 'invalidated',
			    version = version + 1,
			    reviewed_at = clock_timestamp(),
			    review_note = 'source_ineligible',
			    updated_by = :actor
			WHERE candidate_id = :candidateId
			  AND status = 'pending'
			""")
			.param("actor", ACTOR)
			.param("candidateId", candidateId)
			.update();
	}

	public int invalidateIneligiblePendingCandidates() {
		return jdbc.sql("""
			UPDATE knowledge_relation_candidates candidate
			SET status = 'invalidated',
			    version = version + 1,
			    reviewed_at = clock_timestamp(),
			    review_note = 'source_ineligible',
			    updated_by = :actor
			WHERE candidate.status = 'pending'
			  AND NOT EXISTS (
			      SELECT 1
			      FROM knowledge_sources ks
			      JOIN questions q ON q.question_id = ks.question_id
			      JOIN pins p ON p.pin_id = q.pin_id
			      JOIN answers a ON a.answer_id = ks.answer_id AND a.question_id = q.question_id
			      WHERE ks.source_id = candidate.source_id
			        AND ks.source_type = 'accepted_human_answer'
			        AND ks.status = 'ready'
			        AND ks.active
			        AND (ks.valid_until IS NULL OR ks.valid_until > clock_timestamp())
			        AND q.deleted_at IS NULL
			        AND p.deleted_at IS NULL
			        AND p.pin_type = 'question'
			        AND a.is_accepted
			        AND NOT a.is_ai
			        AND a.author_id IS NOT NULL
			        AND btrim(a.content) <> ''
			  )
			""")
			.param("actor", ACTOR)
			.update();
	}

	public List<KnowledgeCandidateListRow> findCandidates(String status, Long cursorId, int limit) {
		String statusFilter = status == null ? "" : "AND candidate.status = :status\n";
		return jdbc.sql("""
			SELECT candidate.candidate_id, candidate.source_id, candidate.evidence_chunk_id,
			       candidate.subject_text, candidate.predicate, candidate.object_text, candidate.confidence,
			       candidate.evidence_excerpt, candidate.status, candidate.version, candidate.reviewer_user_id,
			       candidate.reviewed_at, candidate.review_note, candidate.promotion_relation_id,
			       candidate.created_at, candidate.updated_at,
			       ks.question_id, ks.answer_id, ks.display_name, ks.status AS source_status,
			       ks.active AS source_active, ks.valid_until,
			       EXISTS (
			           SELECT 1
			           FROM questions q
			           JOIN pins p ON p.pin_id = q.pin_id
			           JOIN answers a ON a.answer_id = ks.answer_id AND a.question_id = q.question_id
			           WHERE q.question_id = ks.question_id
			             AND q.deleted_at IS NULL
			             AND p.deleted_at IS NULL
			             AND p.pin_type = 'question'
			             AND a.is_accepted
			             AND NOT a.is_ai
			             AND a.author_id IS NOT NULL
			             AND btrim(a.content) <> ''
			             AND ks.source_type = 'accepted_human_answer'
			             AND ks.status = 'ready'
			             AND ks.active
			             AND (ks.valid_until IS NULL OR ks.valid_until > clock_timestamp())
			       ) AS source_eligible
			FROM knowledge_relation_candidates candidate
			JOIN knowledge_sources ks ON ks.source_id = candidate.source_id
			WHERE (:cursorId IS NULL OR candidate.candidate_id < :cursorId)
			""" + statusFilter + """
			ORDER BY candidate.candidate_id DESC
			LIMIT :limit
			""")
			.param("cursorId", cursorId, Types.BIGINT)
			.param("status", status)
			.param("limit", limit)
			.query(this::mapListRow)
			.list();
	}

	public Optional<KnowledgeCandidateDetailRow> findDetail(long candidateId) {
		return jdbc.sql("""
			SELECT candidate.candidate_id, candidate.source_id, candidate.evidence_chunk_id,
			       candidate.subject_text, candidate.predicate, candidate.object_text, candidate.confidence,
			       candidate.evidence_excerpt, candidate.extraction_provider, candidate.extraction_model,
			       candidate.status, candidate.version, candidate.reviewer_user_id,
			       candidate.reviewed_at, candidate.review_note, candidate.promotion_relation_id,
			       candidate.created_at, candidate.updated_at,
			       ks.question_id, ks.answer_id, ks.display_name, ks.status AS source_status,
			       ks.active AS source_active, ks.valid_until,
			       q.title AS question_title, q.content AS question_content,
			       a.content AS answer_content, kc.content AS chunk_content,
			       EXISTS (
			           SELECT 1
			           FROM pins p
			           WHERE p.pin_id = q.pin_id
			             AND q.deleted_at IS NULL
			             AND p.deleted_at IS NULL
			             AND p.pin_type = 'question'
			             AND a.is_accepted
			             AND NOT a.is_ai
			             AND a.author_id IS NOT NULL
			             AND btrim(a.content) <> ''
			             AND ks.source_type = 'accepted_human_answer'
			             AND ks.status = 'ready'
			             AND ks.active
			             AND (ks.valid_until IS NULL OR ks.valid_until > clock_timestamp())
			       ) AS source_eligible
			FROM knowledge_relation_candidates candidate
			JOIN knowledge_sources ks ON ks.source_id = candidate.source_id
			LEFT JOIN questions q ON q.question_id = ks.question_id
			LEFT JOIN answers a ON a.answer_id = ks.answer_id
			LEFT JOIN knowledge_chunks kc
			       ON kc.source_id = candidate.source_id AND kc.chunk_id = candidate.evidence_chunk_id
			WHERE candidate.candidate_id = :candidateId
			""")
			.param("candidateId", candidateId)
			.query(this::mapDetailRow)
			.optional();
	}

	public List<RelationRow> findRelationsBySource(long sourceId) {
		return jdbc.sql("""
			SELECT relation_id, source_id, subject, predicate, object, confidence, evidence_chunk_id
			FROM knowledge_relations
			WHERE source_id = :sourceId
			ORDER BY relation_id DESC
			""")
			.param("sourceId", sourceId)
			.query(this::mapRelation)
			.list();
	}

	private KnowledgeCandidateRow mapCandidate(ResultSet rs, int rowNum) throws SQLException {
		return new KnowledgeCandidateRow(
			rs.getLong("candidate_id"),
			rs.getLong("source_id"),
			rs.getLong("evidence_chunk_id"),
			rs.getString("subject_text"),
			KnowledgeRelationPredicate.valueOf(rs.getString("predicate")),
			rs.getString("object_text"),
			rs.getBigDecimal("confidence"),
			rs.getString("evidence_excerpt"),
			rs.getString("extraction_provider"),
			rs.getString("extraction_model"),
			rs.getString("status"),
			rs.getInt("version"),
			nullableLong(rs, "reviewer_user_id"),
			rs.getObject("reviewed_at", OffsetDateTime.class),
			rs.getString("review_note"),
			nullableLong(rs, "promotion_relation_id"),
			rs.getObject("created_at", OffsetDateTime.class),
			rs.getObject("updated_at", OffsetDateTime.class)
		);
	}

	private RelationRow mapRelation(ResultSet rs, int rowNum) throws SQLException {
		return new RelationRow(
			rs.getLong("relation_id"),
			rs.getLong("source_id"),
			rs.getString("subject"),
			KnowledgeRelationPredicate.valueOf(rs.getString("predicate")),
			rs.getString("object"),
			rs.getBigDecimal("confidence"),
			nullableLong(rs, "evidence_chunk_id")
		);
	}

	private KnowledgeCandidateListRow mapListRow(ResultSet rs, int rowNum) throws SQLException {
		return new KnowledgeCandidateListRow(
			rs.getLong("candidate_id"),
			rs.getLong("source_id"),
			rs.getLong("evidence_chunk_id"),
			rs.getString("subject_text"),
			KnowledgeRelationPredicate.valueOf(rs.getString("predicate")),
			rs.getString("object_text"),
			rs.getBigDecimal("confidence"),
			rs.getString("evidence_excerpt"),
			rs.getString("status"),
			rs.getInt("version"),
			nullableLong(rs, "reviewer_user_id"),
			rs.getObject("reviewed_at", OffsetDateTime.class),
			rs.getString("review_note"),
			nullableLong(rs, "promotion_relation_id"),
			rs.getObject("created_at", OffsetDateTime.class),
			rs.getObject("updated_at", OffsetDateTime.class),
			rs.getLong("question_id"),
			rs.getLong("answer_id"),
			rs.getString("display_name"),
			rs.getString("source_status"),
			rs.getBoolean("source_active"),
			rs.getObject("valid_until", OffsetDateTime.class),
			rs.getBoolean("source_eligible")
		);
	}

	private KnowledgeCandidateDetailRow mapDetailRow(ResultSet rs, int rowNum) throws SQLException {
		return new KnowledgeCandidateDetailRow(
			rs.getLong("candidate_id"),
			rs.getLong("source_id"),
			rs.getLong("evidence_chunk_id"),
			rs.getString("subject_text"),
			KnowledgeRelationPredicate.valueOf(rs.getString("predicate")),
			rs.getString("object_text"),
			rs.getBigDecimal("confidence"),
			rs.getString("evidence_excerpt"),
			rs.getString("extraction_provider"),
			rs.getString("extraction_model"),
			rs.getString("status"),
			rs.getInt("version"),
			nullableLong(rs, "reviewer_user_id"),
			rs.getObject("reviewed_at", OffsetDateTime.class),
			rs.getString("review_note"),
			nullableLong(rs, "promotion_relation_id"),
			rs.getObject("created_at", OffsetDateTime.class),
			rs.getObject("updated_at", OffsetDateTime.class),
			nullableLong(rs, "question_id"),
			nullableLong(rs, "answer_id"),
			rs.getString("display_name"),
			rs.getString("source_status"),
			rs.getBoolean("source_active"),
			rs.getObject("valid_until", OffsetDateTime.class),
			rs.getBoolean("source_eligible"),
			rs.getString("question_title"),
			rs.getString("question_content"),
			rs.getString("answer_content"),
			rs.getString("chunk_content")
		);
	}

	private Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	public record KnowledgeCandidateRow(
		Long candidateId,
		Long sourceId,
		Long evidenceChunkId,
		String subject,
		KnowledgeRelationPredicate predicate,
		String object,
		BigDecimal confidence,
		String evidenceExcerpt,
		String extractionProvider,
		String extractionModel,
		String status,
		int version,
		Long reviewerUserId,
		OffsetDateTime reviewedAt,
		String reviewNote,
		Long promotionRelationId,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt
	) {
	}

	public record RelationRow(
		Long relationId,
		Long sourceId,
		String subject,
		KnowledgeRelationPredicate predicate,
		String object,
		BigDecimal confidence,
		Long evidenceChunkId
	) {
	}

	public record KnowledgeCandidateListRow(
		Long candidateId,
		Long sourceId,
		Long evidenceChunkId,
		String subject,
		KnowledgeRelationPredicate predicate,
		String object,
		BigDecimal confidence,
		String evidenceExcerpt,
		String status,
		int version,
		Long reviewerUserId,
		OffsetDateTime reviewedAt,
		String reviewNote,
		Long promotionRelationId,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt,
		Long questionId,
		Long answerId,
		String sourceDisplayName,
		String sourceStatus,
		boolean sourceActive,
		OffsetDateTime validUntil,
		boolean sourceEligible
	) {
	}

	public record KnowledgeCandidateDetailRow(
		Long candidateId,
		Long sourceId,
		Long evidenceChunkId,
		String subject,
		KnowledgeRelationPredicate predicate,
		String object,
		BigDecimal confidence,
		String evidenceExcerpt,
		String extractionProvider,
		String extractionModel,
		String status,
		int version,
		Long reviewerUserId,
		OffsetDateTime reviewedAt,
		String reviewNote,
		Long promotionRelationId,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt,
		Long questionId,
		Long answerId,
		String sourceDisplayName,
		String sourceStatus,
		boolean sourceActive,
		OffsetDateTime validUntil,
		boolean sourceEligible,
		String questionTitle,
		String questionContent,
		String answerContent,
		String chunkContent
	) {
	}
}
