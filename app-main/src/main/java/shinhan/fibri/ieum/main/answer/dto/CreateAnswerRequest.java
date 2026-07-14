package shinhan.fibri.ieum.main.answer.dto;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

// content/imageFileIds 둘 다 필드 자체는 선택이지만, 서비스 계층에서 "둘 중 하나 이상"을 강제한다
// (@NotBlank 같은 단일 필드 제약으로는 표현할 수 없는 교차 필드 규칙이라 Bean Validation 대신 AnswerService에서 검증).
public record CreateAnswerRequest(
	@Size(max = 5000)
	String content,

	@Size(max = 10)
	List<UUID> imageFileIds
) {
}
