package shinhan.fibri.ieum.main.chat.dto;

import jakarta.validation.constraints.NotNull;

public record NotifyRoomRequest(
	@NotNull(message = "enabled is required")
	Boolean enabled
) {
}
