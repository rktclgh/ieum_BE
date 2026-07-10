package shinhan.fibri.ieum.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
		@Value("${app.place.ncp-maps.key}") String ncpMapsKey
	) {
		return new PlaceProxyProperties(
			naverSearchBaseUrl,
			naverSearchClientId,
			naverSearchClientSecret,
			ncpMapsBaseUrl,
			ncpMapsKeyId,
			ncpMapsKey
		);
	}

	@Bean
	PlaceProviderBulkhead placeProviderBulkhead() {
		return new PlaceProviderBulkhead(20);
	}

	@Bean
	PlaceSearchClient placeSearchClient(PlaceProxyProperties properties, PlaceProviderBulkhead placeProviderBulkhead) {
		return new NaverLegacyPlaceSearchClient(
			RestClient.builder().baseUrl(properties.naverSearchBaseUrl()).build(),
			properties.naverSearchClientId(),
			properties.naverSearchClientSecret(),
			placeProviderBulkhead
		);
	}

	@Bean
	GeocodingClient geocodingClient(PlaceProxyProperties properties, PlaceProviderBulkhead placeProviderBulkhead) {
		return new NcpMapsGeocodingClient(
			RestClient.builder().baseUrl(properties.ncpMapsBaseUrl()).build(),
			properties.ncpMapsKeyId(),
			properties.ncpMapsKey(),
			placeProviderBulkhead
		);
	}
}
