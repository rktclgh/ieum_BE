package shinhan.fibri.ieum.main.chat.dto;

import jakarta.validation.constraints.NotNull;

public record DirectRoomRequest(
	@NotNull(message = "friendId is required")
	Long friendId
) {
}
