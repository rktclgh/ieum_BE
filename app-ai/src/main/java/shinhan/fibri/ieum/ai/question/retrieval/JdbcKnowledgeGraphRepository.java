package shinhan.fibri.ieum.ai.question.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcKnowledgeGraphRepository implements KnowledgeGraphRepository {

	private static final String ELIGIBLE_SOURCE_PREDICATE = """
		ks.status = 'ready'
		AND ks.active = TRUE
		AND (ks.valid_until IS NULL OR ks.valid_until > now())
		AND (
		    ks.source_type <> 'accepted_human_answer'
		    OR EXISTS (
		        SELECT 1
		        FROM answers accepted_answer
		        JOIN questions accepted_question
		          ON accepted_question.question_id = accepted_answer.question_id
		        JOIN pins accepted_pin
		          ON accepted_pin.pin_id = accepted_question.pin_id
		        WHERE accepted_answer.answer_id = ks.answer_id
		          AND accepted_answer.question_id = ks.question_id
		          AND accepted_answer.is_accepted
		          AND NOT accepted_answer.is_ai
		          AND accepted_answer.author_id IS NOT NULL
		          AND btrim(accepted_answer.content) <> ''
		          AND accepted_question.question_id = ks.question_id
		          AND accepted_question.deleted_at IS NULL
		          AND accepted_pin.deleted_at IS NULL
		          AND accepted_pin.pin_type = 'question'
		    )
		)
		""";
	private static final int MAX_RESULT_LIMIT = 20;

	private final JdbcClient jdbc;
	private final ObjectMapper objectMapper;

	public JdbcKnowledgeGraphRepository(JdbcClient jdbc) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
		this.objectMapper = new ObjectMapper();
	}

	@Override
	public List<KnowledgeGraphCandidate> findOneHopCandidates(
		List<String> canonicalEntityCandidates,
		int limit
	) {
		return findOneHopCandidates(canonicalEntityCandidates, null, limit);
	}

	@Override
	public List<KnowledgeGraphCandidate> findOneHopCandidates(
		List<String> canonicalEntityCandidates,
		GeoPoint coordinates,
		int limit
	) {
		List<String> entities = validatedEntities(canonicalEntityCandidates);
		return jdbc.sql("""
			WITH input_entities AS (
			    SELECT input.entity,
			           input.ordinality::integer AS input_rank
			    FROM jsonb_array_elements_text(CAST(:entityCandidates AS jsonb))
			         WITH ORDINALITY AS input(entity, ordinality)
			), matched_relations AS (
			    SELECT input.entity AS matched_entity,
			           'subject'::text AS matched_side,
			           0 AS side_priority,
			           input.input_rank,
			           kr.relation_id,
			           kr.source_id,
			           kr.subject,
			           kr.predicate,
			           kr.object,
			           kr.confidence AS relation_confidence,
			           kr.evidence_chunk_id
			    FROM input_entities input
			    JOIN knowledge_relations kr ON kr.subject = input.entity
			    WHERE kr.predicate IN (:allowedPredicates)
			    UNION ALL
			    SELECT input.entity AS matched_entity,
			           'object'::text AS matched_side,
			           1 AS side_priority,
			           input.input_rank,
			           kr.relation_id,
			           kr.source_id,
			           kr.subject,
			           kr.predicate,
			           kr.object,
			           kr.confidence AS relation_confidence,
			           kr.evidence_chunk_id
			    FROM input_entities input
			    JOIN knowledge_relations kr ON kr.object = input.entity
			    WHERE kr.predicate IN (:allowedPredicates)
			), selected_matches AS (
			    SELECT matched_relations.*,
			           row_number() OVER (
			               PARTITION BY relation_id
			               ORDER BY input_rank ASC, side_priority ASC
			           ) AS match_priority
			    FROM matched_relations
			), eligible_matches AS (
			    SELECT selected.matched_entity,
			           selected.matched_side,
			           selected.side_priority,
			           selected.input_rank,
			           selected.relation_id,
			           selected.source_id,
			           kc.chunk_id,
			           selected.subject,
			           selected.predicate,
			           selected.object,
			           selected.relation_confidence,
			           ks.source_type::text AS source_type,
			           ks.display_name,
			           kc.content,
			           ks.metadata ->> 'sourceGrade' AS source_grade,
			           ks.content_hash,
			           ks.metadata ->> 'canonicalUrl' AS canonical_url,
			           ks.metadata ->> 'riskDomain' AS risk_domain,
			           ks.geo_scope,
			           ks.region_context ->> 'sido' AS source_sido,
			           ks.region_context ->> 'sigungu' AS source_sigungu,
			           CASE
			               WHEN CAST(:latitude AS double precision) IS NULL
			                   OR CAST(:longitude AS double precision) IS NULL
			                   OR ks.anchor_location IS NULL
			               THEN NULL::double precision
			               ELSE ST_Distance(
			                   ks.anchor_location,
			                   ST_SetSRID(ST_MakePoint(
			                       CAST(:longitude AS double precision),
			                       CAST(:latitude AS double precision)
			                   ), 4326)::geography
			               ) / 1000.0
			           END AS distance_km
			    FROM selected_matches selected
			    JOIN knowledge_sources ks ON ks.source_id = selected.source_id
			    JOIN knowledge_chunks kc
			      ON kc.source_id = selected.source_id
			     AND kc.chunk_id = selected.evidence_chunk_id
			    WHERE selected.match_priority = 1
			      AND selected.evidence_chunk_id IS NOT NULL
			      AND kc.embedding_model = 'gemini-embedding-2'
			      AND %s
			)
			SELECT matched_entity,
			       matched_side,
			       relation_id,
			       source_id,
			       chunk_id,
			       subject,
			       predicate,
			       object,
			       relation_confidence,
			       source_type,
			       display_name,
			       content,
			       source_grade,
			       content_hash,
			       canonical_url,
			       risk_domain,
			       geo_scope,
			       source_sido,
			       source_sigungu,
			       distance_km
			FROM eligible_matches
			ORDER BY relation_confidence DESC,
			         input_rank ASC,
			         source_id ASC,
			         chunk_id ASC,
			         relation_id ASC,
			         side_priority ASC
			LIMIT :limit
			""".formatted(ELIGIBLE_SOURCE_PREDICATE))
			.param("entityCandidates", jsonArray(entities))
			.param("allowedPredicates", KnowledgeGraphCandidate.ALLOWED_PREDICATES)
			.param("longitude", coordinates == null ? null : coordinates.longitude())
			.param("latitude", coordinates == null ? null : coordinates.latitude())
			.param("limit", positiveLimit(limit))
			.query(this::mapCandidate)
			.list();
	}

	@Override
	public Set<Long> findEligibleRelationIds(Collection<Long> relationIds) {
		LinkedHashSet<Long> distinctIds = validatedRelationIds(relationIds);
		if (distinctIds.isEmpty()) {
			return Set.of();
		}
		List<Long> eligible = jdbc.sql("""
			SELECT kr.relation_id
			FROM knowledge_relations kr
			JOIN knowledge_sources ks ON ks.source_id = kr.source_id
			JOIN knowledge_chunks kc
			  ON kc.source_id = kr.source_id
			 AND kc.chunk_id = kr.evidence_chunk_id
			WHERE kr.relation_id IN (:relationIds)
			  AND kr.evidence_chunk_id IS NOT NULL
			  AND kr.predicate IN (:allowedPredicates)
			  AND kc.embedding_model = 'gemini-embedding-2'
			  AND %s
			ORDER BY kr.relation_id ASC
			""".formatted(ELIGIBLE_SOURCE_PREDICATE))
			.param("relationIds", distinctIds)
			.param("allowedPredicates", KnowledgeGraphCandidate.ALLOWED_PREDICATES)
			.query(Long.class)
			.list();
		return Collections.unmodifiableSet(new LinkedHashSet<>(eligible));
	}

	private KnowledgeGraphCandidate mapCandidate(ResultSet resultSet, int rowNumber) throws SQLException {
		double distance = resultSet.getDouble("distance_km");
		Double distanceKm = resultSet.wasNull() ? null : distance;
		return new KnowledgeGraphCandidate(
			resultSet.getString("matched_entity"),
			resultSet.getString("matched_side"),
			resultSet.getLong("relation_id"),
			resultSet.getLong("source_id"),
			resultSet.getLong("chunk_id"),
			resultSet.getString("subject"),
			resultSet.getString("predicate"),
			resultSet.getString("object"),
			resultSet.getBigDecimal("relation_confidence"),
			resultSet.getString("source_type"),
			resultSet.getString("display_name"),
			resultSet.getString("content"),
			resultSet.getString("source_grade"),
			resultSet.getString("content_hash"),
			resultSet.getString("canonical_url"),
			resultSet.getString("risk_domain"),
			GeoScope.fromDatabaseValue(resultSet.getString("geo_scope")),
			new RegionContext(
				resultSet.getString("source_sido"),
				resultSet.getString("source_sigungu")
			),
			distanceKm
		);
	}

	private List<String> validatedEntities(List<String> entities) {
		if (entities == null) {
			throw new IllegalArgumentException("canonicalEntityCandidates must not be null");
		}
		if (entities.isEmpty()) {
			throw new IllegalArgumentException("canonicalEntityCandidates must not be empty");
		}
		for (String entity : entities) {
			if (entity == null || entity.isBlank()) {
				throw new IllegalArgumentException("canonicalEntityCandidates must contain nonblank values");
			}
			if (entity.length() > KnowledgeGraphRetrievalService.MAX_ENTITY_LENGTH) {
				throw new IllegalArgumentException("canonical entity candidate must not exceed 200 characters");
			}
			if (!entity.equals(entity.trim())) {
				throw new IllegalArgumentException("canonical entity candidates must already be trimmed");
			}
		}
		return List.copyOf(entities);
	}

	private LinkedHashSet<Long> validatedRelationIds(Collection<Long> relationIds) {
		if (relationIds == null) {
			throw new IllegalArgumentException("relationIds must not be null");
		}
		LinkedHashSet<Long> distinctIds = new LinkedHashSet<>();
		for (Long relationId : relationIds) {
			if (relationId == null || relationId <= 0L) {
				throw new IllegalArgumentException("relationIds must contain only positive values");
			}
			distinctIds.add(relationId);
		}
		return distinctIds;
	}

	private String jsonArray(List<String> entities) {
		try {
			return objectMapper.writeValueAsString(entities);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("canonicalEntityCandidates could not be serialized", exception);
		}
	}

	private int positiveLimit(int limit) {
		if (limit <= 0 || limit > MAX_RESULT_LIMIT) {
			throw new IllegalArgumentException("limit must be between 1 and 20");
		}
		return limit;
	}
}
