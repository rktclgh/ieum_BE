package shinhan.fibri.ieum.main.question.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record QuestionCreateRequest(
	@NotBlank @Size(max = 200) String title,
	@NotBlank @Size(max = 5000) String content,
	@NotNull @Valid QuestionLocation location,
	@Size(max = 10) List<UUID> imageFileIds
) {
}
