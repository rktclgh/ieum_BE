package shinhan.fibri.ieum.main.answer.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.main.answer.domain.Answer;

public interface AnswerRepository extends JpaRepository<Answer, Long> {

	boolean existsByQuestionIdAndAuthorIdAndAiFalse(Long questionId, Long authorId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select a from Answer a
		where a.questionId = :questionId and a.id in :answerIds
		order by a.id asc
		""")
	List<Answer> findAllByQuestionIdAndIdInForUpdate(
		@Param("questionId") Long questionId,
		@Param("answerIds") List<Long> answerIds
	);

	@Query("""
		select a.id from Answer a
		where a.questionId = :questionId and a.accepted = true
		order by a.id asc
		""")
	List<Long> findAcceptedIdsByQuestionIdOrderByIdAsc(@Param("questionId") Long questionId);
}
