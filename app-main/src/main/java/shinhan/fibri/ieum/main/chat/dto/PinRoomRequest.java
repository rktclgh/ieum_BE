package shinhan.fibri.ieum.main.chat.dto;

import jakarta.validation.constraints.NotNull;

public record PinRoomRequest(
	@NotNull(message = "pinned is required")
	Boolean pinned
) {
}
