package shinhan.fibri.ieum.main.pin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import shinhan.fibri.ieum.main.pin.domain.PinType;

public record PinListRequest(
	PinType type,
	String cursor,

	@Min(value = 1, message = "Size must be greater than or equal to 1")
	@Max(value = 50, message = "Size must be less than or equal to 50")
	Integer size
) {
}
