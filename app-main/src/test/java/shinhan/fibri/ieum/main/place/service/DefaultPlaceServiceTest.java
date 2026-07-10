package shinhan.fibri.ieum.main.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultPlaceServiceTest {

	private final PlaceSearchClient searchClient = mock(PlaceSearchClient.class);
	private final GeocodingClient geocodingClient = mock(GeocodingClient.class);
	private final DefaultPlaceService service = new DefaultPlaceService(searchClient, geocodingClient);

	@Test
	void mapsNaverCandidateIntoStablePlaceDto() {
		when(searchClient.search("시청")).thenReturn(List.of(new PlaceSearchCandidate(
			"<b>서울</b>특별시청 &amp; 광장",
			"서울특별시 중구 세종대로 110",
			"서울특별시 중구 태평로1가 31",
			"1269783882",
			"375666103",
			"공공,사회기관"
		)));

		var place = service.search("시청", null, null).places().getFirst();

		assertThat(place.name()).isEqualTo("서울특별시청 & 광장");
		assertThat(place.address()).isEqualTo("서울특별시 중구 세종대로 110");
		assertThat(place.lat()).isEqualTo(37.5666103);
		assertThat(place.lng()).isEqualTo(126.9783882);
		assertThat(place.categoryGroupName()).isEqualTo("공공,사회기관");
		assertThat(place.id()).startsWith("naver:").hasSize(38);
	}

	@Test
	void preservesProviderOrderWhenNearCoordinateIsAbsent() {
		when(searchClient.search("카페")).thenReturn(List.of(candidate("첫째", "1269783882", "375666103"), candidate("둘째", "1269780000", "375660000")));

		assertThat(service.search("카페", null, null).places()).extracting(place -> place.name())
			.containsExactly("첫째", "둘째");
	}

	@Test
	void sortsByDistanceStablyWhenNearCoordinateIsProvided() {
		when(searchClient.search("카페")).thenReturn(List.of(candidate("먼곳", "1270000000", "376000000"), candidate("가까운곳", "1269783882", "375666103")));

		assertThat(service.search("카페", 37.5666, 126.9784).places()).extracting(place -> place.name())
			.containsExactly("가까운곳", "먼곳");
	}

	@Test
	void skipsProviderForBlankSearchQuery() {
		assertThat(service.search("", null, null).places()).isEmpty();

		verify(searchClient, never()).search(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void skipsMalformedProviderCandidatesAndKeepsValidCandidates() {
		when(searchClient.search("시청")).thenReturn(List.of(
			candidate("깨진 후보", "not-a-number", "375666103"),
			candidate("시청", "1269783882", "375666103")
		));

		assertThat(service.search("시청", null, null).places()).extracting(place -> place.name())
			.containsExactly("시청");
	}

	@Test
	void preservesProviderOrderWhenOnlyOneNearCoordinateReachesService() {
		when(searchClient.search("카페")).thenReturn(List.of(candidate("첫째", "1269783882", "375666103")));

		assertThat(service.search("카페", 37.5666, null).places()).extracting(place -> place.name())
			.containsExactly("첫째");
	}

	@Test
	void keepsOnlyKoreanGeocodeCandidates() {
		when(geocodingClient.geocode("서울특별시청")).thenReturn(List.of(
			new GeocodeCandidate("서울특별시 중구 세종대로 110", "서울특별시 중구 태평로1가 31", 37.5666103, 126.9783882),
			new GeocodeCandidate("New York", "New York", 40.7128, -74.0060)
		));

		assertThat(service.geocode("서울특별시청").addresses()).containsExactly(
			new shinhan.fibri.ieum.main.place.dto.GeocodedAddressResponse(
				"서울특별시 중구 세종대로 110", "서울특별시 중구 태평로1가 31", 37.5666103, 126.9783882
			)
		);
	}

	@Test
	void returnsNullReverseResponseWhenProviderHasNoMatch() {
		when(geocodingClient.reverseGeocode(37.5666, 126.9784)).thenReturn(new ReverseGeocodeResult(null, null));

		assertThat(service.reverseGeocode(37.5666, 126.9784))
			.isEqualTo(new shinhan.fibri.ieum.main.place.dto.ReverseGeocodeResponse(null, null));
	}

	private static PlaceSearchCandidate candidate(String name, String mapx, String mapy) {
		return new PlaceSearchCandidate(name, "도로명 주소", "지번 주소", mapx, mapy, null);
	}
}
