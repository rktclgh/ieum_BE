package shinhan.fibri.ieum.main.place.dto;

import java.util.List;

public record GeocodeResponse(List<GeocodedAddressResponse> addresses) {
	public static GeocodeResponse empty() {
		return new GeocodeResponse(List.of());
	}
}
