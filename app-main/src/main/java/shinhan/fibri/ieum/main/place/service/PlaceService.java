package shinhan.fibri.ieum.main.place.service;

import shinhan.fibri.ieum.main.place.dto.GeocodeResponse;
import shinhan.fibri.ieum.main.place.dto.PlaceSearchResponse;
import shinhan.fibri.ieum.main.place.dto.ReverseGeocodeResponse;

public interface PlaceService {

	PlaceSearchResponse search(String query, Double latitude, Double longitude);

	GeocodeResponse geocode(String query);

	ReverseGeocodeResponse reverseGeocode(double latitude, double longitude);
}
