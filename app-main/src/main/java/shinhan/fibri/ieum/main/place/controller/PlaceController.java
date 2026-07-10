package shinhan.fibri.ieum.main.place.controller;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.main.place.dto.GeocodeResponse;
import shinhan.fibri.ieum.main.place.dto.PlaceSearchResponse;
import shinhan.fibri.ieum.main.place.dto.ReverseGeocodeResponse;
import shinhan.fibri.ieum.main.place.service.PlaceService;
import shinhan.fibri.ieum.main.place.exception.PlaceRateLimitedException;
import shinhan.fibri.ieum.main.place.support.PlaceOperation;
import shinhan.fibri.ieum.main.place.support.PlaceClientKeyFactory;
import shinhan.fibri.ieum.main.place.support.PlaceRateLimiter;

@RestController
@RequestMapping("/api/places")
@RequiredArgsConstructor
public class PlaceController {

	private final PlaceService placeService;
	private final PlaceRateLimiter placeRateLimiter;
	private final PlaceClientKeyFactory placeClientKeyFactory;

	@GetMapping("/search")
	public ResponseEntity<PlaceSearchResponse> search(
		@RequestParam(required = false) String query,
		@RequestParam(required = false) Double lat,
		@RequestParam(required = false) Double lng,
		HttpServletRequest request
	) {
		String normalizedQuery = PlaceRequestValidator.normalizeQuery(query);
		PlaceRequestValidator.validateOptionalCoordinates(lat, lng);
		checkRateLimit(PlaceOperation.search, request);
		return ResponseEntity.ok(placeService.search(normalizedQuery, lat, lng));
	}

	@GetMapping("/geocode")
	public ResponseEntity<GeocodeResponse> geocode(@RequestParam(required = false) String query, HttpServletRequest request) {
		checkRateLimit(PlaceOperation.geocode, request);
		return ResponseEntity.ok(placeService.geocode(PlaceRequestValidator.normalizeQuery(query)));
	}

	@GetMapping("/reverse-geocode")
	public ResponseEntity<ReverseGeocodeResponse> reverseGeocode(
		@RequestParam(required = false) Double lat,
		@RequestParam(required = false) Double lng,
		HttpServletRequest request
	) {
		PlaceRequestValidator.validateRequiredCoordinates(lat, lng);
		checkRateLimit(PlaceOperation.reverse, request);
		return ResponseEntity.ok(placeService.reverseGeocode(lat, lng));
	}

	private void checkRateLimit(PlaceOperation operation, HttpServletRequest request) {
		if (!placeRateLimiter.tryAcquire(operation, placeClientKeyFactory.anonymousClientKey(request.getRemoteAddr()))) {
			throw new PlaceRateLimitedException();
		}
	}
}
