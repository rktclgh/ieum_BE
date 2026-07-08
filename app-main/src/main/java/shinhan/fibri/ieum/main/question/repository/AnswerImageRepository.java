package shinhan.fibri.ieum.main.question.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.main.question.domain.QuestionImage;

// answer_images 테이블 전용이지만 AnswerImage 엔티티가 아직 없어 QuestionImage를
// 도메인 타입 자리표시자로 사용한다(순수 네이티브 @Query만 사용, JPA 메타데이터 미의존).
// answer 슬라이스에서 AnswerImage 엔티티가 추가되면 그 타입으로 교체한다.
public interface AnswerImageRepository extends Repository<QuestionImage, Long> {

	@Query(value = "SELECT EXISTS (SELECT 1 FROM answer_images WHERE file_id = :fileId)", nativeQuery = true)
	boolean existsByFileId(@Param("fileId") UUID fileId);
}
