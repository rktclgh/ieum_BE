package shinhan.fibri.ieum.main.pin.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.main.pin.domain.Pin;

public interface PinRepository extends JpaRepository<Pin, Long> {

	@Query(
		value = """
			SELECT p.pin_id                                     AS "pinId",
			       CAST(p.pin_type AS text)                     AS "pinType",
			       COALESCE(q.question_id, m.meeting_id)         AS "targetId",
			       COALESCE(q.title, m.title)                   AS "title",
			       COALESCE(m.thumbnail_file_id, m.image_file_id, qi.file_id) AS "thumbnailFileId",
			       ST_Y(p.location::geometry)                   AS "latitude",
			       ST_X(p.location::geometry)                   AS "longitude",
			       (p.author_id = :userId)                      AS "mine",
			       p.created_at                                 AS "createdAt"
			FROM pins p
			LEFT JOIN questions q ON q.pin_id = p.pin_id AND q.is_resolved = false
			LEFT JOIN meetings  m ON m.pin_id = p.pin_id AND m.deleted_at IS NULL
			LEFT JOIN LATERAL (
			   SELECT file_id FROM question_images qi
			   WHERE qi.question_id = q.question_id ORDER BY qi.sort_order LIMIT 1
			) qi ON true
			WHERE p.deleted_at IS NULL
			  AND (:type IS NULL OR CAST(p.pin_type AS text) = :type)
			  AND (q.question_id IS NOT NULL OR m.meeting_id IS NOT NULL)
			  AND p.location && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326)::geography
			  AND NOT EXISTS (
			     SELECT 1 FROM friendships f
			     WHERE f.status = 'blocked'
			       AND ((f.requester_id = :userId AND f.addressee_id = p.author_id)
			         OR (f.addressee_id = :userId AND f.requester_id = p.author_id)))
			ORDER BY p.pin_id DESC
			LIMIT :maxItems
			""",
		nativeQuery = true
	)
	List<PinProjection> findMapPins(
		@Param("userId") Long userId,
		@Param("type") String type,
		@Param("swLat") double swLat,
		@Param("swLng") double swLng,
		@Param("neLat") double neLat,
		@Param("neLng") double neLng,
		@Param("maxItems") int maxItems
	);

	@Query(
		value = """
			SELECT p.pin_id                                     AS "pinId",
			       CAST(p.pin_type AS text)                     AS "pinType",
			       COALESCE(q.question_id, m.meeting_id)         AS "targetId",
			       COALESCE(q.title, m.title)                   AS "title",
			       COALESCE(m.thumbnail_file_id, m.image_file_id, qi.file_id) AS "thumbnailFileId",
			       ST_Y(p.location::geometry)                   AS "latitude",
			       ST_X(p.location::geometry)                   AS "longitude",
			       (p.author_id = :userId)                      AS "mine",
			       p.created_at                                 AS "createdAt"
			FROM pins p
			LEFT JOIN questions q ON q.pin_id = p.pin_id AND q.is_resolved = false
			LEFT JOIN meetings  m ON m.pin_id = p.pin_id AND m.deleted_at IS NULL
			LEFT JOIN LATERAL (
			   SELECT file_id FROM question_images qi
			   WHERE qi.question_id = q.question_id ORDER BY qi.sort_order LIMIT 1
			) qi ON true
			WHERE p.deleted_at IS NULL
			  AND (:type IS NULL OR CAST(p.pin_type AS text) = :type)
			  AND (q.question_id IS NOT NULL OR m.meeting_id IS NOT NULL)
			  AND (:cursorId IS NULL OR p.pin_id < :cursorId)
			  AND NOT EXISTS (
			     SELECT 1 FROM friendships f
			     WHERE f.status = 'blocked'
			       AND ((f.requester_id = :userId AND f.addressee_id = p.author_id)
			         OR (f.addressee_id = :userId AND f.requester_id = p.author_id)))
			ORDER BY p.pin_id DESC
			LIMIT :sizePlusOne
			""",
		nativeQuery = true
	)
	List<PinProjection> findListPins(
		@Param("userId") Long userId,
		@Param("type") String type,
		@Param("cursorId") Long cursorId,
		@Param("sizePlusOne") int sizePlusOne
	);
}
