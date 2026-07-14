package shinhan.fibri.ieum.main.place.dto;

import java.util.List;

public record PlaceSearchResponse(List<PlaceResponse> places) {
	public static PlaceSearchResponse empty() {
		return new PlaceSearchResponse(List.of());
	}
}
