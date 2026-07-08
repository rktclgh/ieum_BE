package shinhan.fibri.ieum.main.pin.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.main.pin.domain.PinType;

public record PinItem(
	Long pinId,
	PinType pinType,
	String title,
	String thumbnailUrl,
	PinLocation location,
	boolean mine,
	OffsetDateTime createdAt
) {
}
