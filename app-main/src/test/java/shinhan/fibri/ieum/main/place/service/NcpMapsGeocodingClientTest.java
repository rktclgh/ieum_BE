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

class NcpMapsGeocodingClientTest {

	@Test
	void sendsGeocodeRequestAndKeepsDegreeCoordinatesWithoutRescaling() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://maps.apigw.ntruss.com");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		NcpMapsGeocodingClient client = new NcpMapsGeocodingClient(builder.build(), "key-id", "key");
		server.expect(requestTo(URI.create("https://maps.apigw.ntruss.com/map-geocode/v2/geocode?query=%EC%84%9C%EC%9A%B8%ED%8A%B9%EB%B3%84%EC%8B%9C%EC%B2%AD&count=5")))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header("x-ncp-apigw-api-key-id", "key-id"))
			.andExpect(header("x-ncp-apigw-api-key", "key"))
			.andRespond(withSuccess("""
				{
				  "meta": { "totalCount": 1 },
				  "addresses": [{
				    "roadAddress": "서울특별시 중구 세종대로 110",
				    "jibunAddress": "서울특별시 중구 태평로1가 31",
				    "x": "126.9783882",
				    "y": "37.5666103"
				  }]
				}
				""", MediaType.APPLICATION_JSON));

		assertThat(client.geocode("서울특별시청")).containsExactly(new GeocodeCandidate(
			"서울특별시 중구 세종대로 110",
			"서울특별시 중구 태평로1가 31",
			37.5666103,
			126.9783882
		));
		server.verify();
	}

	@Test
	void sendsReverseCoordinatesInLongitudeLatitudeOrderAndBuildsRoadAddress() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://maps.apigw.ntruss.com");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		NcpMapsGeocodingClient client = new NcpMapsGeocodingClient(builder.build(), "key-id", "key");
		server.expect(requestTo(URI.create("https://maps.apigw.ntruss.com/map-reversegeocode/v2/gc?coords=126.9784,37.5666&orders=roadaddr,addr&output=json")))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("""
				{
				  "status": { "code": 0 },
				  "results": [{
				    "name": "roadaddr",
				    "region": {
				      "area1": { "name": "서울특별시" },
				      "area2": { "name": "중구" },
				      "area3": { "name": "태평로1가" }
				    },
				    "land": {
				      "name": "세종대로",
				      "number1": "110",
				      "number2": "",
				      "addition0": { "type": "building", "value": "서울특별시청" }
				    }
				  }]
				}
				""", MediaType.APPLICATION_JSON));

		assertThat(client.reverseGeocode(37.5666, 126.9784)).isEqualTo(
			new ReverseGeocodeResult("서울특별시 중구 세종대로 110 (서울특별시청)", "태평로1가")
		);
		server.verify();
	}
}
