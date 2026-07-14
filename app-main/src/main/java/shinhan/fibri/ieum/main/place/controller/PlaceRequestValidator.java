package shinhan.fibri.ieum.main.place.controller;

import org.springframework.http.HttpStatus;
import shinhan.fibri.ieum.main.place.exception.PlaceRequestException;

final class PlaceRequestValidator {

	private static final int MAX_QUERY_CODE_POINTS = 100;
	private static final double MIN_LATITUDE = 33d;
	private static final double MAX_LATITUDE = 39d;
	private static final double MIN_LONGITUDE = 124d;
	private static final double MAX_LONGITUDE = 132d;

	private PlaceRequestValidator() {
	}

	static String normalizeQuery(String query) {
		String normalized = query == null ? "" : query.trim();
		if (normalized.codePointCount(0, normalized.length()) > MAX_QUERY_CODE_POINTS) {
			throw new PlaceRequestException(HttpStatus.BAD_REQUEST, "INVALID_PLACE_QUERY", "query", "Place query is too long");
		}
		return normalized;
	}

	static void validateOptionalCoordinates(Double latitude, Double longitude) {
		if (latitude == null && longitude == null) {
			return;
		}
		validateRequiredCoordinates(latitude, longitude);
	}

	static void validateRequiredCoordinates(Double latitude, Double longitude) {
		if (latitude == null || longitude == null || !Double.isFinite(latitude) || !Double.isFinite(longitude)) {
			throw new PlaceRequestException(HttpStatus.BAD_REQUEST, "INVALID_COORDINATES", "coordinates", "Invalid coordinates");
		}
		if (latitude < MIN_LATITUDE || latitude > MAX_LATITUDE || longitude < MIN_LONGITUDE || longitude > MAX_LONGITUDE) {
			throw new PlaceRequestException(HttpStatus.BAD_REQUEST, "OUTSIDE_SERVICE_AREA", "coordinates", "Outside service area");
		}
	}
}
