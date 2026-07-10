package shinhan.fibri.ieum.main.place.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import shinhan.fibri.ieum.main.place.dto.GeocodeResponse;
import shinhan.fibri.ieum.main.place.dto.PlaceResponse;
import shinhan.fibri.ieum.main.place.dto.PlaceSearchResponse;
import shinhan.fibri.ieum.main.place.dto.ReverseGeocodeResponse;
import shinhan.fibri.ieum.main.place.exception.PlaceProviderException;

@Service
public class DefaultPlaceService implements PlaceService {

	private static final double EARTH_RADIUS_METERS = 6_371_000d;
	private final PlaceSearchClient placeSearchClient;
	private final GeocodingClient geocodingClient;

	public DefaultPlaceService(PlaceSearchClient placeSearchClient, GeocodingClient geocodingClient) {
		this.placeSearchClient = placeSearchClient;
		this.geocodingClient = geocodingClient;
	}

	@Override
	public PlaceSearchResponse search(String query, Double latitude, Double longitude) {
		if (query.isBlank()) {
			return PlaceSearchResponse.empty();
		}
		List<PlaceResponse> places = placeSearchClient.search(query).stream()
			.map(this::toPlaceResponseSafely)
			.flatMap(Optional::stream)
			.toList();
		if (latitude == null || longitude == null) {
			return new PlaceSearchResponse(places);
		}
		return new PlaceSearchResponse(places.stream()
			.sorted(Comparator.comparingDouble(place -> distanceMeters(latitude, longitude, place.lat(), place.lng())))
			.toList());
	}

	@Override
	public GeocodeResponse geocode(String query) {
		if (query.isBlank()) {
			return GeocodeResponse.empty();
		}
		return new GeocodeResponse(geocodingClient.geocode(query).stream()
			.filter(candidate -> inKoreaServiceArea(candidate.latitude(), candidate.longitude()))
			.map(candidate -> new shinhan.fibri.ieum.main.place.dto.GeocodedAddressResponse(
				candidate.roadAddress(),
				candidate.jibunAddress(),
				candidate.latitude(),
				candidate.longitude()
			))
			.toList());
	}

	@Override
	public ReverseGeocodeResponse reverseGeocode(double latitude, double longitude) {
		ReverseGeocodeResult result = geocodingClient.reverseGeocode(latitude, longitude);
		return new ReverseGeocodeResponse(result.fullAddress(), result.shortLabel());
	}

	private PlaceResponse toPlaceResponse(PlaceSearchCandidate candidate) {
		double longitude = parseCoordinate(candidate.mapx(), 10_000_000d, 180d);
		double latitude = parseCoordinate(candidate.mapy(), 10_000_000d, 90d);
		String name = cleanHtml(candidate.title());
		String address = firstNonBlank(cleanHtml(candidate.roadAddress()), cleanHtml(candidate.address()));
		return new PlaceResponse(
			stableId(name, address, candidate.mapx(), candidate.mapy()),
			name,
			address,
			latitude,
			longitude,
			normalizeOptional(candidate.category())
		);
	}

	private Optional<PlaceResponse> toPlaceResponseSafely(PlaceSearchCandidate candidate) {
		try {
			return Optional.of(toPlaceResponse(candidate));
		} catch (PlaceProviderException exception) {
			return Optional.empty();
		}
	}

	private double parseCoordinate(String value, double divisor, double maximum) {
		try {
			double coordinate = Long.parseLong(value) / divisor;
			if (!Double.isFinite(coordinate) || Math.abs(coordinate) > maximum) {
				throw new NumberFormatException("coordinate out of range");
			}
			return coordinate;
		} catch (RuntimeException exception) {
			throw new PlaceProviderException("Place provider returned invalid coordinates");
		}
	}

	private String cleanHtml(String value) {
		return HtmlUtils.htmlUnescape(value == null ? "" : value.replace("<b>", "").replace("</b>", "")).trim();
	}

	private String firstNonBlank(String first, String second) {
		return !first.isBlank() ? first : second;
	}

	private String normalizeOptional(String value) {
		String normalized = cleanHtml(value);
		return normalized.isBlank() ? null : normalized;
	}

	private String stableId(String name, String address, String mapx, String mapy) {
		String source = String.join("|", name.toLowerCase(Locale.ROOT), address.toLowerCase(Locale.ROOT), mapx, mapy);
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
			return "naver:" + HexFormat.of().formatHex(digest, 0, 16);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available", exception);
		}
	}

	private double distanceMeters(double firstLatitude, double firstLongitude, double secondLatitude, double secondLongitude) {
		double latitudeDelta = Math.toRadians(secondLatitude - firstLatitude);
		double longitudeDelta = Math.toRadians(secondLongitude - firstLongitude);
		double a = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
			+ Math.cos(Math.toRadians(firstLatitude)) * Math.cos(Math.toRadians(secondLatitude))
			* Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
		return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
	}

	private boolean inKoreaServiceArea(double latitude, double longitude) {
		return latitude >= 33d && latitude <= 39d && longitude >= 124d && longitude <= 132d;
	}
}
