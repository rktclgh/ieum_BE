package shinhan.fibri.ieum.main.place.controller;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
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
		@AuthenticationPrincipal AuthenticatedUser principal,
		HttpServletRequest request
	) {
		String normalizedQuery = PlaceRequestValidator.normalizeQuery(query);
		PlaceRequestValidator.validateOptionalCoordinates(lat, lng);
		if (normalizedQuery.isBlank()) {
			return ResponseEntity.ok(PlaceSearchResponse.empty());
		}
		checkRateLimit(PlaceOperation.search, principal, request);
		return ResponseEntity.ok(placeService.search(normalizedQuery, lat, lng));
	}

	@GetMapping("/geocode")
	public ResponseEntity<GeocodeResponse> geocode(
		@RequestParam(required = false) String query,
		@AuthenticationPrincipal AuthenticatedUser principal,
		HttpServletRequest request
	) {
		String normalizedQuery = PlaceRequestValidator.normalizeQuery(query);
		if (normalizedQuery.isBlank()) {
			return ResponseEntity.ok(GeocodeResponse.empty());
		}
		checkRateLimit(PlaceOperation.geocode, principal, request);
		return ResponseEntity.ok(placeService.geocode(normalizedQuery));
	}

	@GetMapping("/reverse-geocode")
	public ResponseEntity<ReverseGeocodeResponse> reverseGeocode(
		@RequestParam(required = false) Double lat,
		@RequestParam(required = false) Double lng,
		@AuthenticationPrincipal AuthenticatedUser principal,
		HttpServletRequest request
	) {
		PlaceRequestValidator.validateRequiredCoordinates(lat, lng);
		checkRateLimit(PlaceOperation.reverse, principal, request);
		return ResponseEntity.ok(placeService.reverseGeocode(lat, lng));
	}

	private void checkRateLimit(PlaceOperation operation, AuthenticatedUser principal, HttpServletRequest request) {
		if (!placeRateLimiter.tryAcquire(operation, placeClientKeyFactory.clientKey(principal, request.getRemoteAddr()))) {
			throw new PlaceRateLimitedException();
		}
	}
}
