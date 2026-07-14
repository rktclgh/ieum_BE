package shinhan.fibri.ieum.main.chat.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record QuestionRoomRequest(
	@NotNull(message = "questionId is required")
	@Positive(message = "questionId must be positive")
	Long questionId,
	@NotNull(message = "targetUserId is required")
	@Positive(message = "targetUserId must be positive")
	Long targetUserId
) {
}
