package shinhan.fibri.ieum.main.file.dto;

import java.net.URI;
import java.util.UUID;

public record FilePresignResponse(
	UUID fileId,
	URI uploadUrl
) {
}
