package shinhan.fibri.ieum.main.pin.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import shinhan.fibri.ieum.main.pin.domain.PinType;

public record PinMapRequest(
	@NotNull(message = "Southwest latitude is required")
	@DecimalMin(value = "-90.0", message = "Southwest latitude must be greater than or equal to -90")
	@DecimalMax(value = "90.0", message = "Southwest latitude must be less than or equal to 90")
	Double swLat,

	@NotNull(message = "Southwest longitude is required")
	@DecimalMin(value = "-180.0", message = "Southwest longitude must be greater than or equal to -180")
	@DecimalMax(value = "180.0", message = "Southwest longitude must be less than or equal to 180")
	Double swLng,

	@NotNull(message = "Northeast latitude is required")
	@DecimalMin(value = "-90.0", message = "Northeast latitude must be greater than or equal to -90")
	@DecimalMax(value = "90.0", message = "Northeast latitude must be less than or equal to 90")
	Double neLat,

	@NotNull(message = "Northeast longitude is required")
	@DecimalMin(value = "-180.0", message = "Northeast longitude must be greater than or equal to -180")
	@DecimalMax(value = "180.0", message = "Northeast longitude must be less than or equal to 180")
	Double neLng,

	PinType type
) {
}
