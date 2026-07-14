package shinhan.fibri.ieum.main.place.service;

import java.util.List;

public interface GeocodingClient {

	List<GeocodeCandidate> geocode(String query);

	ReverseGeocodeResult reverseGeocode(double latitude, double longitude);
}
