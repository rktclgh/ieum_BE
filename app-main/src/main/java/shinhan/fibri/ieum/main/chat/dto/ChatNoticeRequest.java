package shinhan.fibri.ieum.main.chat.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ChatNoticeRequest(
	@NotNull
	@Positive
	Long messageId
) {
}
