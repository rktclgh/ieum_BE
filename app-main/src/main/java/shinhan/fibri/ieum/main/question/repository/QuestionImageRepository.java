package shinhan.fibri.ieum.main.question.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import shinhan.fibri.ieum.main.question.domain.QuestionImage;

public interface QuestionImageRepository extends JpaRepository<QuestionImage, Long> {

	List<QuestionImage> findByQuestionIdOrderBySortOrderAsc(Long questionId);

	boolean existsByFileId(java.util.UUID fileId);

	@Modifying
	void deleteByQuestionId(Long questionId);
}
