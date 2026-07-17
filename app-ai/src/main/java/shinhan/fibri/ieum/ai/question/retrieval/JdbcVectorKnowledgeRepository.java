package shinhan.fibri.ieum.ai.question.retrieval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcVectorKnowledgeRepository implements VectorKnowledgeRepository {

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
		          AND accepted_pin.pin_type = 'question'
		    )
		)
		""";

	private final JdbcClient jdbc;

	public JdbcVectorKnowledgeRepository(JdbcClient jdbc) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
	}

	@Override
	public List<VectorKnowledgeCandidate> findGlobalCandidates(List<Float> embedding, int limit) {
		return jdbc.sql("""
			SELECT ks.source_id,
			       kc.chunk_id,
			       ks.source_type::text AS source_type,
			       ks.display_name,
			       kc.content,
			       ks.metadata ->> 'sourceGrade' AS source_grade,
			       ks.content_hash,
			       ks.metadata ->> 'canonicalUrl' AS canonical_url,
			       ks.metadata ->> 'riskDomain' AS risk_domain,
			       ks.metadata ->> 'domain' AS source_domain,
			       ks.geo_scope,
			       ks.region_context ->> 'sido' AS source_sido,
			       ks.region_context ->> 'sigungu' AS source_sigungu,
			       1 - (kc.embedding <=> CAST(:embedding AS vector)) AS cosine_similarity,
			       NULL::double precision AS distance_km
			FROM knowledge_chunks kc
			JOIN knowledge_sources ks ON ks.source_id = kc.source_id
			WHERE %s
			  AND kc.embedding_model = 'gemini-embedding-2'
			ORDER BY kc.embedding <=> CAST(:embedding AS vector) ASC,
			         ks.source_id ASC,
			         kc.chunk_id ASC
			LIMIT :limit
			""".formatted(ELIGIBLE_SOURCE_PREDICATE))
			.param("embedding", vectorLiteral(embedding))
			.param("limit", positiveLimit(limit))
			.query(this::mapCandidate)
			.list();
	}

	@Override
	public List<VectorKnowledgeCandidate> findLocationAwareCandidates(
		List<Float> embedding,
		RegionContext regionContext,
		GeoPoint coordinates,
		int limit
	) {
		RegionContext queryRegion = regionContext == null ? RegionContext.empty() : regionContext;
		return jdbc.sql("""
			WITH candidate_base AS (
			    SELECT ks.source_id,
			           kc.chunk_id,
			           ks.source_type::text AS source_type,
			           ks.display_name,
			           kc.content,
			           ks.metadata ->> 'sourceGrade' AS source_grade,
			           ks.content_hash,
			           ks.metadata ->> 'canonicalUrl' AS canonical_url,
			           ks.metadata ->> 'riskDomain' AS risk_domain,
			           ks.metadata ->> 'domain' AS source_domain,
			           ks.geo_scope,
			           ks.region_context ->> 'sido' AS source_sido,
			           ks.region_context ->> 'sigungu' AS source_sigungu,
			           kc.embedding <=> CAST(:embedding AS vector) AS cosine_distance,
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
			    FROM knowledge_chunks kc
			    JOIN knowledge_sources ks ON ks.source_id = kc.source_id
			    WHERE %s
			      AND kc.embedding_model = 'gemini-embedding-2'
			      AND ks.geo_scope IN ('regional', 'local', 'place_specific')
			), scored AS (
			    SELECT candidate_base.*,
			           CASE geo_scope
			               WHEN 'regional' THEN CASE
			                   WHEN CAST(:sido AS text) IS NULL OR source_sido IS NULL THEN 0.5
			                   WHEN CAST(:sido AS text) = source_sido
			                       AND CAST(:sigungu AS text) IS NOT NULL
			                       AND CAST(:sigungu AS text) = source_sigungu THEN 1.0
			                   WHEN CAST(:sido AS text) = source_sido THEN 0.7
			                   ELSE 0.2
			               END
			               WHEN 'local' THEN COALESCE(exp(-distance_km / 10.0), 0.5)
			               WHEN 'place_specific' THEN COALESCE(exp(-distance_km / 2.0), 0.5)
			               ELSE 0.5
			           END AS geo_score
			    FROM candidate_base
			)
			SELECT source_id,
			       chunk_id,
			       source_type,
			       display_name,
			       content,
			       source_grade,
			       content_hash,
			       canonical_url,
			       risk_domain,
			       source_domain,
			       geo_scope,
			       source_sido,
			       source_sigungu,
			       1 - cosine_distance AS cosine_similarity,
			       distance_km
			FROM scored
			ORDER BY geo_score DESC,
			         cosine_distance ASC,
			         source_id ASC,
			         chunk_id ASC
			LIMIT :limit
			""".formatted(ELIGIBLE_SOURCE_PREDICATE))
			.param("embedding", vectorLiteral(embedding))
			.param("sido", queryRegion.sido())
			.param("sigungu", queryRegion.sigungu())
			.param("longitude", coordinates == null ? null : coordinates.longitude())
			.param("latitude", coordinates == null ? null : coordinates.latitude())
			.param("limit", positiveLimit(limit))
			.query(this::mapCandidate)
			.list();
	}

	@Override
	public Set<Long> findEligibleChunkIds(Collection<Long> chunkIds) {
		Objects.requireNonNull(chunkIds, "chunkIds must not be null");
		LinkedHashSet<Long> distinctIds = new LinkedHashSet<>(chunkIds);
		if (distinctIds.isEmpty()) {
			return Set.of();
		}
		List<Long> eligible = jdbc.sql("""
			SELECT kc.chunk_id
			FROM knowledge_chunks kc
			JOIN knowledge_sources ks ON ks.source_id = kc.source_id
			WHERE kc.chunk_id IN (:chunkIds)
			  AND kc.embedding_model = 'gemini-embedding-2'
			  AND %s
			ORDER BY kc.chunk_id ASC
			""".formatted(ELIGIBLE_SOURCE_PREDICATE))
			.param("chunkIds", distinctIds)
			.query(Long.class)
			.list();
		return Set.copyOf(eligible);
	}

	private VectorKnowledgeCandidate mapCandidate(ResultSet resultSet, int rowNumber) throws SQLException {
		double distance = resultSet.getDouble("distance_km");
		Double distanceKm = resultSet.wasNull() ? null : distance;
		return new VectorKnowledgeCandidate(
			resultSet.getLong("source_id"),
			resultSet.getLong("chunk_id"),
			resultSet.getString("source_type"),
			resultSet.getString("display_name"),
			resultSet.getString("content"),
			resultSet.getString("source_grade"),
			resultSet.getString("content_hash"),
			resultSet.getString("canonical_url"),
			resultSet.getString("risk_domain"),
			resultSet.getString("source_domain"),
			GeoScope.fromDatabaseValue(resultSet.getString("geo_scope")),
			new RegionContext(
				resultSet.getString("source_sido"),
				resultSet.getString("source_sigungu")
			),
			resultSet.getDouble("cosine_similarity"),
			distanceKm
		);
	}

	private String vectorLiteral(List<Float> embedding) {
		Objects.requireNonNull(embedding, "embedding must not be null");
		if (embedding.size() != VectorKnowledgeRetrievalRequest.EMBEDDING_DIMENSIONS) {
			throw new IllegalArgumentException("embedding must contain exactly 768 values");
		}
		StringJoiner literal = new StringJoiner(",", "[", "]");
		for (Float value : embedding) {
			if (value == null || !Float.isFinite(value)) {
				throw new IllegalArgumentException("embedding values must be finite");
			}
			literal.add(Float.toString(value));
		}
		return literal.toString();
	}

	private int positiveLimit(int limit) {
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		return limit;
	}
}
