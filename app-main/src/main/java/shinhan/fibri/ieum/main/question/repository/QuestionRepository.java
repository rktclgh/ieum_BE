package shinhan.fibri.ieum.main.question.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.main.question.domain.Question;

import jakarta.persistence.LockModeType;

public interface QuestionRepository extends JpaRepository<Question, Long> {

	@Query(
		value = """
			SELECT q.question_id AS questionId,
			       q.title AS title,
			       q.content AS content,
			       q.is_resolved AS resolved,
			       u.user_id AS authorId,
			       u.nickname AS authorNickname,
			       u.profile_file_id AS authorProfileFileId,
			       u.nationality AS authorNationality,
			       ST_Y(p.location::geometry) AS latitude,
			       ST_X(p.location::geometry) AS longitude,
			       p.address AS address,
			       p.detail_address AS detailAddress,
			       p.label AS label,
			       q.created_at AS createdAt,
			       q.updated_at AS updatedAt
			FROM questions q
			JOIN users u ON u.user_id = q.author_id
			JOIN pins p ON p.pin_id = q.pin_id AND p.deleted_at IS NULL
			WHERE q.question_id = :questionId
			  AND q.deleted_at IS NULL
			""",
		nativeQuery = true
	)
	Optional<QuestionDetailProjection> findDetailByQuestionId(@Param("questionId") Long questionId);

	@Query(
		value = """
			SELECT q.question_id AS questionId,
			       q.title AS title,
			       q.is_resolved AS resolved,
			       first_image.file_id AS thumbnailFileId,
			       CAST((SELECT COUNT(*) FROM answers a WHERE a.question_id = q.question_id) AS int) AS answerCount,
			       q.created_at AS createdAt
			FROM questions q
			LEFT JOIN LATERAL (
			    SELECT qi.file_id
			    FROM question_images qi
			    WHERE qi.question_id = q.question_id
			    ORDER BY qi.sort_order
			    LIMIT 1
			) first_image ON true
			WHERE q.author_id = :authorId
			  AND q.deleted_at IS NULL
			ORDER BY q.question_id DESC
			LIMIT :limit
			""",
		nativeQuery = true
	)
	List<MyQuestionItemProjection> findMineFirstPage(
		@Param("authorId") Long authorId,
		@Param("limit") int limit
	);

	@Query(
		value = """
			SELECT q.question_id AS questionId,
			       q.title AS title,
			       q.is_resolved AS resolved,
			       first_image.file_id AS thumbnailFileId,
			       CAST((SELECT COUNT(*) FROM answers a WHERE a.question_id = q.question_id) AS int) AS answerCount,
			       q.created_at AS createdAt
			FROM questions q
			LEFT JOIN LATERAL (
			    SELECT qi.file_id
			    FROM question_images qi
			    WHERE qi.question_id = q.question_id
			    ORDER BY qi.sort_order
			    LIMIT 1
			) first_image ON true
			WHERE q.author_id = :authorId
			  AND q.deleted_at IS NULL
			  AND q.question_id < :cursor
			ORDER BY q.question_id DESC
			LIMIT :limit
			""",
		nativeQuery = true
	)
	List<MyQuestionItemProjection> findMineAfterCursor(
		@Param("authorId") Long authorId,
		@Param("cursor") Long cursor,
		@Param("limit") int limit
	);

	@Query(
		value = """
			SELECT a.answer_id AS answerId,
			       a.is_ai AS ai,
			       u.user_id AS authorId,
			       u.nickname AS authorNickname,
			       u.profile_file_id AS authorProfileFileId,
			       u.nationality AS authorNationality,
			       a.content AS content,
			       a.is_accepted AS accepted,
			       a.created_at AS createdAt
			FROM answers a
			LEFT JOIN users u ON u.user_id = a.author_id
			WHERE a.question_id = :questionId
			ORDER BY a.created_at ASC, a.answer_id ASC
			""",
		nativeQuery = true
	)
	List<AnswerItemProjection> findAnswersByQuestionId(@Param("questionId") Long questionId);

	@Query(
		value = """
			SELECT q.author_id AS authorId,
			       q.deleted_at AS deletedAt
			  FROM questions q
			 WHERE q.question_id = :questionId
			""",
		nativeQuery = true
	)
	Optional<QuestionDeletionState> findDeletionState(@Param("questionId") Long questionId);

	@Query("""
		SELECT q.id AS questionId,
		       q.title AS title
		FROM Question q
		WHERE q.id IN :questionIds
		  AND q.deletedAt IS NULL
		""")
	List<QuestionTitleProjection> findTitlesByIds(@Param("questionIds") Collection<Long> questionIds);

	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select q from Question q where q.id = :questionId")
	Optional<Question> findActiveByIdForShare(@Param("questionId") Long questionId);

	@Query("select q from Question q where q.id = :questionId and q.deletedAt IS NULL")
	Optional<Question> findActiveByIdForTranslation(@Param("questionId") Long questionId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select q from Question q where q.id = :questionId")
	Optional<Question> findByIdForUpdate(@Param("questionId") Long questionId);
}
