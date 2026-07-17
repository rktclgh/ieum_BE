package shinhan.fibri.ieum.main.question.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import shinhan.fibri.ieum.main.question.domain.QuestionImage;

public interface QuestionImageRepository extends JpaRepository<QuestionImage, Long> {

	List<QuestionImage> findByQuestionIdOrderBySortOrderAsc(Long questionId);

	void deleteByQuestionId(Long questionId);
}
