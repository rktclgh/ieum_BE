package shinhan.fibri.ieum.main.question.dto;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record QuestionUpdateRequest(
	@Size(max = 200) String title,
	@Size(max = 5000) String content,
	@Size(max = 10) List<UUID> imageFileIds
) {
}
