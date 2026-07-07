package shinhan.fibri.ieum.main.user.dto;

import java.util.UUID;

public record UpdateProfileImageRequest(
	UUID fileId
) {
}
