package shinhan.fibri.ieum.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import shinhan.fibri.ieum.main.place.service.GeocodingClient;
import shinhan.fibri.ieum.main.place.service.NaverLegacyPlaceSearchClient;
import shinhan.fibri.ieum.main.place.service.NcpMapsGeocodingClient;
import shinhan.fibri.ieum.main.place.service.PlaceSearchClient;
import shinhan.fibri.ieum.main.place.support.PlaceProviderBulkhead;

@Configuration
public class PlaceProxyConfig {

	@Bean
	PlaceProxyProperties placeProxyProperties(
		@Value("${app.place.naver-search.base-url}") String naverSearchBaseUrl,
		@Value("${app.place.naver-search.client-id}") String naverSearchClientId,
		@Value("${app.place.naver-search.client-secret}") String naverSearchClientSecret,
		@Value("${app.place.ncp-maps.base-url}") String ncpMapsBaseUrl,
		@Value("${app.place.ncp-maps.key-id}") String ncpMapsKeyId,
		@Value("${app.place.ncp-maps.key}") String ncpMapsKey,
		@Value("${app.place.http.connect-timeout-ms}") int connectTimeoutMs,
		@Value("${app.place.http.read-timeout-ms}") int readTimeoutMs,
		@Value("${app.place.http.max-concurrent}") int maxConcurrent
	) {
		return new PlaceProxyProperties(
			naverSearchBaseUrl,
			naverSearchClientId,
			naverSearchClientSecret,
			ncpMapsBaseUrl,
			ncpMapsKeyId,
			ncpMapsKey,
			connectTimeoutMs,
			readTimeoutMs,
			maxConcurrent
		);
	}

	@Bean
	PlaceProviderBulkhead placeProviderBulkhead(PlaceProxyProperties properties) {
		return new PlaceProviderBulkhead(properties.maxConcurrent());
	}

	@Bean
	PlaceSearchClient placeSearchClient(PlaceProxyProperties properties, PlaceProviderBulkhead placeProviderBulkhead) {
		return new NaverLegacyPlaceSearchClient(
			restClient(properties.naverSearchBaseUrl(), properties),
			properties.naverSearchClientId(),
			properties.naverSearchClientSecret(),
			placeProviderBulkhead
		);
	}

	@Bean
	GeocodingClient geocodingClient(PlaceProxyProperties properties, PlaceProviderBulkhead placeProviderBulkhead) {
		return new NcpMapsGeocodingClient(
			restClient(properties.ncpMapsBaseUrl(), properties),
			properties.ncpMapsKeyId(),
			properties.ncpMapsKey(),
			placeProviderBulkhead
		);
	}

	private RestClient restClient(String baseUrl, PlaceProxyProperties properties) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.connectTimeoutMs());
		requestFactory.setReadTimeout(properties.readTimeoutMs());
		return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
	}
}
