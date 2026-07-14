package shinhan.fibri.ieum.main.place.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.place.dto.GeocodeResponse;
import shinhan.fibri.ieum.main.place.dto.GeocodedAddressResponse;
import shinhan.fibri.ieum.main.place.dto.PlaceResponse;
import shinhan.fibri.ieum.main.place.dto.PlaceSearchResponse;
import shinhan.fibri.ieum.main.place.dto.ReverseGeocodeResponse;
import shinhan.fibri.ieum.main.place.service.PlaceService;
import shinhan.fibri.ieum.main.place.support.PlaceRateLimiter;
import shinhan.fibri.ieum.main.place.support.PlaceClientKeyFactory;

@WebMvcTest(PlaceController.class)
@AutoConfigureMockMvc(addFilters = false)
class PlaceControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PlaceService placeService;

	@Autowired
	private PlaceRateLimiter placeRateLimiter;

	@BeforeEach
	void allowPlaceRequestsByDefault() {
		when(placeRateLimiter.tryAcquire(any(), any())).thenReturn(true);
	}

	@Test
	void returnsStableSearchResponseShape() throws Exception {
		when(placeService.search("시청", 37.5666, 126.9784)).thenReturn(new PlaceSearchResponse(List.of(
			new PlaceResponse("naver:place", "서울특별시청", "서울특별시 중구 세종대로 110", 37.5666103, 126.9783882, "공공")
		)));

		mockMvc.perform(get("/api/places/search")
				.param("query", "시청")
				.param("lat", "37.5666")
				.param("lng", "126.9784"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.places[0].id", is("naver:place")))
			.andExpect(jsonPath("$.places[0].lat", is(37.5666103)))
			.andExpect(jsonPath("$.places[0].lng", is(126.9783882)));
	}

	@Test
	void rejectsSearchWhenOnlyOneNearCoordinateIsProvided() throws Exception {
		mockMvc.perform(get("/api/places/search")
				.param("query", "시청")
				.param("lat", "37.5666"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("INVALID_COORDINATES")));

		verify(placeService, never()).search(any(), any(), any());
	}

	@Test
	void rejectsNonFiniteReverseGeocodeCoordinate() throws Exception {
		mockMvc.perform(get("/api/places/reverse-geocode")
				.param("lat", "NaN")
				.param("lng", "126.9784"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("INVALID_COORDINATES")));
	}

	@Test
	void rejectsReverseGeocodeOutsideKoreaServiceArea() throws Exception {
		mockMvc.perform(get("/api/places/reverse-geocode")
				.param("lat", "40.7128")
				.param("lng", "-74.0060"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("OUTSIDE_SERVICE_AREA")));
	}

	@Test
	void returnsRateLimitedResponseBeforeCallingProviderService() throws Exception {
		when(placeRateLimiter.tryAcquire(any(), any())).thenReturn(false);

		mockMvc.perform(get("/api/places/search").param("query", "시청"))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().string("Retry-After", "60"))
			.andExpect(jsonPath("$.code", is("PLACE_RATE_LIMITED")));

		verify(placeService, never()).search(any(), any(), any());
	}

	@Test
	void returnsEmptySearchWithoutConsumingRateLimitForBlankQuery() throws Exception {
		clearInvocations(placeRateLimiter, placeService);

		mockMvc.perform(get("/api/places/search").param("query", "   "))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.places").isEmpty());

		verify(placeRateLimiter, never()).tryAcquire(any(), any());
		verify(placeService, never()).search(any(), any(), any());
	}

	@Test
	void returnsEmptyGeocodeWithoutConsumingRateLimitForBlankQuery() throws Exception {
		clearInvocations(placeRateLimiter, placeService);

		mockMvc.perform(get("/api/places/geocode").param("query", "   "))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.addresses").isEmpty());

		verify(placeRateLimiter, never()).tryAcquire(any(), any());
		verify(placeService, never()).geocode(any());
	}

	@Test
	void returnsStableGeocodeAndReverseGeocodeResponseShapes() throws Exception {
		when(placeService.geocode("서울특별시청")).thenReturn(new GeocodeResponse(List.of(
			new GeocodedAddressResponse("서울특별시 중구 세종대로 110", "서울특별시 중구 태평로1가 31", 37.5666103, 126.9783882)
		)));
		when(placeService.reverseGeocode(37.5666, 126.9784))
			.thenReturn(new ReverseGeocodeResponse("서울특별시 중구 세종대로 110 (서울특별시청)", "태평로1가"));

		mockMvc.perform(get("/api/places/geocode").param("query", "서울특별시청"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.addresses[0].roadAddress", is("서울특별시 중구 세종대로 110")));
		mockMvc.perform(get("/api/places/reverse-geocode")
				.param("lat", "37.5666")
				.param("lng", "126.9784"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.shortLabel", is("태평로1가")));
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		PlaceService placeService() {
			return mock(PlaceService.class);
		}

		@Bean
		@Primary
		PlaceRateLimiter placeRateLimiter() {
			return mock(PlaceRateLimiter.class);
		}

		@Bean
		@Primary
		PlaceClientKeyFactory placeClientKeyFactory() {
			return mock(PlaceClientKeyFactory.class);
		}

		@Bean
		@Primary
		SessionTokenValidator sessionTokenValidator() {
			return mock(SessionTokenValidator.class);
		}

		@Bean
		WebMvcConfigurer authenticationPrincipalArgumentResolverConfigurer() {
			return new WebMvcConfigurer() {
				@Override
				public void addArgumentResolvers(java.util.List<HandlerMethodArgumentResolver> resolvers) {
					resolvers.add(new AuthenticationPrincipalArgumentResolver());
				}
			};
		}
	}
}
