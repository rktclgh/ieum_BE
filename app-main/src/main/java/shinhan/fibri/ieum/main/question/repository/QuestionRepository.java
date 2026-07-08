package shinhan.fibri.ieum.main.question.repository;

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
			       u.profile_file_id AS authorProfileFileId
			FROM questions q
			JOIN users u ON u.user_id = q.author_id
			WHERE q.question_id = :questionId
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
			  AND (:cursor IS NULL OR q.question_id < :cursor)
			ORDER BY q.question_id DESC
			LIMIT :limit
			""",
		nativeQuery = true
	)
	List<MyQuestionItemProjection> findMine(
		@Param("authorId") Long authorId,
		@Param("cursor") Long cursor,
		@Param("limit") int limit
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select q from Question q where q.id = :questionId")
	Optional<Question> findByIdForUpdate(@Param("questionId") Long questionId);
}
