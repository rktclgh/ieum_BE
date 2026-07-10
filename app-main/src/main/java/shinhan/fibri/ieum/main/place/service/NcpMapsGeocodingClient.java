package shinhan.fibri.ieum.main.place.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import shinhan.fibri.ieum.main.place.exception.PlaceProviderException;
import shinhan.fibri.ieum.main.place.support.PlaceProviderBulkhead;
import tools.jackson.databind.JsonNode;

public class NcpMapsGeocodingClient implements GeocodingClient {

	private final RestClient restClient;
	private final String keyId;
	private final String key;
	private final PlaceProviderBulkhead bulkhead;

	public NcpMapsGeocodingClient(RestClient restClient, String keyId, String key) {
		this(restClient, keyId, key, new PlaceProviderBulkhead(20));
	}

	public NcpMapsGeocodingClient(RestClient restClient, String keyId, String key, PlaceProviderBulkhead bulkhead) {
		this.restClient = restClient;
		this.keyId = keyId;
		this.key = key;
		this.bulkhead = bulkhead;
	}

	@Override
	public List<GeocodeCandidate> geocode(String query) {
		try {
			JsonNode response = bulkhead.execute(() -> restClient.get()
				.uri(uriBuilder -> uriBuilder.path("/map-geocode/v2/geocode")
					.queryParam("query", query)
					.queryParam("count", 5)
					.build())
				.header("Accept", "application/json")
				.header("x-ncp-apigw-api-key-id", keyId)
				.header("x-ncp-apigw-api-key", key)
				.retrieve()
				.body(JsonNode.class));
			if (response == null || !response.path("addresses").isArray()) {
				return List.of();
			}
			List<GeocodeCandidate> candidates = new ArrayList<>();
			for (JsonNode address : response.path("addresses")) {
				try {
					double longitude = parseCoordinate(text(address, "x"), 180d);
					double latitude = parseCoordinate(text(address, "y"), 90d);
					candidates.add(new GeocodeCandidate(
						text(address, "roadAddress"),
						text(address, "jibunAddress"),
						latitude,
						longitude
					));
				} catch (PlaceProviderException ignored) {
					// A malformed provider candidate is excluded; an all-invalid response is rejected by the service.
				}
			}
			return List.copyOf(candidates);
		} catch (RestClientException exception) {
			throw new PlaceProviderException("Place provider request failed");
		}
	}

	@Override
	public ReverseGeocodeResult reverseGeocode(double latitude, double longitude) {
		try {
			JsonNode response = bulkhead.execute(() -> restClient.get()
				.uri(uriBuilder -> uriBuilder.path("/map-reversegeocode/v2/gc")
					.queryParam("coords", longitude + "," + latitude)
					.queryParam("orders", "roadaddr,addr")
					.queryParam("output", "json")
					.build())
				.header("x-ncp-apigw-api-key-id", keyId)
				.header("x-ncp-apigw-api-key", key)
				.retrieve()
				.body(JsonNode.class));
			if (response == null) {
				throw new PlaceProviderException("Place provider returned invalid response");
			}
			int status = response.path("status").path("code").asInt(-1);
			if (status == 3 || !response.path("results").isArray() || response.path("results").isEmpty()) {
				return new ReverseGeocodeResult(null, null);
			}
			if (status != 0) {
				throw new PlaceProviderException("Place provider returned invalid response");
			}
			JsonNode selected = selectAddressResult(response.path("results"));
			if (selected == null) {
				return new ReverseGeocodeResult(null, null);
			}
			return new ReverseGeocodeResult(
				"roadaddr".equals(text(selected, "name")) ? roadAddress(selected) : jibunAddress(selected),
				shortLabel(selected)
			);
		} catch (RestClientException exception) {
			throw new PlaceProviderException("Place provider request failed");
		}
	}

	private JsonNode selectAddressResult(JsonNode results) {
		for (JsonNode result : results) {
			if ("roadaddr".equals(text(result, "name"))) {
				return result;
			}
		}
		for (JsonNode result : results) {
			if ("addr".equals(text(result, "name"))) {
				return result;
			}
		}
		return null;
	}

	private String roadAddress(JsonNode result) {
		JsonNode region = result.path("region");
		JsonNode land = result.path("land");
		List<String> parts = new ArrayList<>(List.of(text(region.path("area1"), "name"), text(region.path("area2"), "name")));
		String area3 = text(region.path("area3"), "name");
		if (area3.endsWith("읍") || area3.endsWith("면")) {
			parts.add(area3);
		}
		String street = text(land, "name");
		String number = text(land, "number1");
		if (!street.isBlank() || !number.isBlank()) {
			String number2 = text(land, "number2");
			parts.add(joinNonBlank(" ", street, number + (number2.isBlank() ? "" : "-" + number2)));
		}
		String address = joinNonBlank(" ", parts.toArray(String[]::new));
		JsonNode addition = land.path("addition0");
		if ("building".equals(text(addition, "type")) && !text(addition, "value").isBlank()) {
			address += " (" + text(addition, "value") + ")";
		}
		return address;
	}

	private String jibunAddress(JsonNode result) {
		JsonNode region = result.path("region");
		JsonNode land = result.path("land");
		List<String> parts = new ArrayList<>(List.of(
			text(region.path("area1"), "name"),
			text(region.path("area2"), "name"),
			text(region.path("area3"), "name"),
			text(region.path("area4"), "name")
		));
		String number = text(land, "number1");
		if (!number.isBlank()) {
			String prefix = "2".equals(text(land, "type")) ? "산 " : "";
			String number2 = text(land, "number2");
			parts.add(prefix + number + (number2.isBlank() ? "" : "-" + number2));
		}
		return joinNonBlank(" ", parts.toArray(String[]::new));
	}

	private String shortLabel(JsonNode result) {
		JsonNode region = result.path("region");
		String area3 = text(region.path("area3"), "name");
		return area3.isBlank() ? nullIfBlank(text(region.path("area2"), "name")) : area3;
	}

	private double parseCoordinate(String value, double maximum) {
		try {
			double coordinate = Double.parseDouble(value);
			if (!Double.isFinite(coordinate) || Math.abs(coordinate) > maximum) {
				throw new NumberFormatException("coordinate out of range");
			}
			return coordinate;
		} catch (RuntimeException exception) {
			throw new PlaceProviderException("Place provider returned invalid coordinates");
		}
	}

	private String text(JsonNode node, String field) {
		return node.path(field).asText("").trim();
	}

	private String joinNonBlank(String delimiter, String... parts) {
		return java.util.Arrays.stream(parts).filter(part -> part != null && !part.isBlank()).collect(java.util.stream.Collectors.joining(delimiter));
	}

	private String nullIfBlank(String value) {
		return value.isBlank() ? null : value;
	}
}
