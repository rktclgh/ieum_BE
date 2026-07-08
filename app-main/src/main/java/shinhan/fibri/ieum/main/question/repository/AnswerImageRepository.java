package shinhan.fibri.ieum.main.question.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.main.question.domain.QuestionImage;

public interface AnswerImageRepository extends Repository<QuestionImage, Long> {

	@Query(value = "SELECT EXISTS (SELECT 1 FROM answer_images WHERE file_id = :fileId)", nativeQuery = true)
	boolean existsByFileId(@Param("fileId") UUID fileId);
}
