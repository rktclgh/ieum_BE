package shinhan.fibri.ieum.main.pin.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LocationSnapshot(
	@NotNull @DecimalMin("33.0") @DecimalMax("39.0") Double lat,
	@NotNull @DecimalMin("124.0") @DecimalMax("132.0") Double lng,
	@NotBlank @Size(max = 255) String address,
	@Size(max = 200) String detailAddress,
	@Size(max = 100) String label
) {
	public LocationSnapshot normalized() {
		return new LocationSnapshot(lat, lng, address.trim(), normalizeOptional(detailAddress), normalizeOptional(label));
	}

	private static String normalizeOptional(String value) {
		return value == null ? "" : value.trim();
	}
}
