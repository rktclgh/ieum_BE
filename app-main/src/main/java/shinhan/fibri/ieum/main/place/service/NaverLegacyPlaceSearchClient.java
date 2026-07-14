package shinhan.fibri.ieum.main.place.service;

import java.util.List;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import shinhan.fibri.ieum.main.place.exception.PlaceProviderException;
import shinhan.fibri.ieum.main.place.support.PlaceProviderBulkhead;

public class NaverLegacyPlaceSearchClient implements PlaceSearchClient {

	private final RestClient restClient;
	private final String clientId;
	private final String clientSecret;
	private final PlaceProviderBulkhead bulkhead;

	public NaverLegacyPlaceSearchClient(RestClient restClient, String clientId, String clientSecret) {
		this(restClient, clientId, clientSecret, new PlaceProviderBulkhead(20));
	}

	public NaverLegacyPlaceSearchClient(RestClient restClient, String clientId, String clientSecret, PlaceProviderBulkhead bulkhead) {
		this.restClient = restClient;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.bulkhead = bulkhead;
	}

	@Override
	public List<PlaceSearchCandidate> search(String query) {
		try {
			NaverSearchResponse response = bulkhead.execute(() -> restClient.get()
				.uri(uriBuilder -> uriBuilder
					.path("/v1/search/local.json")
					.queryParam("query", query)
					.queryParam("display", 5)
					.queryParam("start", 1)
					.queryParam("sort", "random")
					.build())
				.header("X-Naver-Client-Id", clientId)
				.header("X-Naver-Client-Secret", clientSecret)
				.retrieve()
				.body(NaverSearchResponse.class));
			if (response == null || response.items() == null) {
				return List.of();
			}
			return response.items().stream()
				.map(item -> new PlaceSearchCandidate(
					item.title(),
					item.roadAddress(),
					item.address(),
					item.mapx(),
					item.mapy(),
					item.category()
				))
				.toList();
		} catch (RestClientException exception) {
			throw new PlaceProviderException("Place provider request failed");
		}
	}

	private record NaverSearchResponse(List<NaverSearchItem> items) {
	}

	private record NaverSearchItem(
		String title,
		String roadAddress,
		String address,
		String mapx,
		String mapy,
		String category
	) {
	}
}
