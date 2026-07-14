package shinhan.fibri.ieum.main.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NaverLegacyPlaceSearchClientTest {

	@Test
	void sendsFixedSearchParametersAndMapsProviderItems() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://openapi.naver.com");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		NaverLegacyPlaceSearchClient client = new NaverLegacyPlaceSearchClient(builder.build(), "client-id", "client-secret");
		server.expect(requestTo(URI.create("https://openapi.naver.com/v1/search/local.json?query=%EC%8B%9C%EC%B2%AD&display=5&start=1&sort=random")))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header("X-Naver-Client-Id", "client-id"))
			.andExpect(header("X-Naver-Client-Secret", "client-secret"))
			.andRespond(withSuccess("""
				{
				  "items": [{
				    "title": "<b>서울</b>특별시청",
				    "roadAddress": "서울특별시 중구 세종대로 110",
				    "address": "서울특별시 중구 태평로1가 31",
				    "mapx": "1269783882",
				    "mapy": "375666103",
				    "category": "공공"
				  }]
				}
				""", MediaType.APPLICATION_JSON));

		var candidates = client.search("시청");

		assertThat(candidates).containsExactly(new PlaceSearchCandidate(
			"<b>서울</b>특별시청",
			"서울특별시 중구 세종대로 110",
			"서울특별시 중구 태평로1가 31",
			"1269783882",
			"375666103",
			"공공"
		));
		server.verify();
	}
}
