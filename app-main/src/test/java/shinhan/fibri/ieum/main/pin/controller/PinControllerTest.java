package shinhan.fibri.ieum.main.pin.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.pin.domain.PinType;
import shinhan.fibri.ieum.main.pin.dto.CursorPage;
import shinhan.fibri.ieum.main.pin.dto.PinItem;
import shinhan.fibri.ieum.main.pin.dto.PinListRequest;
import shinhan.fibri.ieum.main.pin.dto.PinLocation;
import shinhan.fibri.ieum.main.pin.dto.PinMapRequest;
import shinhan.fibri.ieum.main.pin.dto.PinMapResponse;
import shinhan.fibri.ieum.main.pin.exception.InvalidPinRequestException;
import shinhan.fibri.ieum.main.pin.service.PinService;

@WebMvcTest(PinController.class)
@AutoConfigureMockMvc(addFilters = false)
class PinControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PinService pinService;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
		reset(pinService);
	}

	@Test
	void getMapPinsReturnsItemsAndTruncatedFlag() throws Exception {
		when(pinService.getMapPins(any(AuthenticatedUser.class), any(PinMapRequest.class)))
			.thenReturn(new PinMapResponse(List.of(pinItem()), true));

		mockMvc.perform(get("/api/v1/pins")
				.with(authenticated())
				.param("swLat", "37.0")
				.param("swLng", "126.0")
				.param("neLat", "38.0")
				.param("neLng", "128.0")
				.param("type", "meeting"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.truncated", is(true)))
			.andExpect(jsonPath("$.items[0].pinId", is(10)))
			.andExpect(jsonPath("$.items[0].pinType", is("meeting")))
			.andExpect(jsonPath("$.items[0].thumbnailUrl", is("/api/v1/files/file-id?v=thumb")))
			.andExpect(jsonPath("$.items[0].location.latitude", is(37.55)))
			.andExpect(jsonPath("$.items[0].authorId").doesNotExist())
			.andExpect(jsonPath("$.items[0].mine", is(true)));
	}

	@Test
	void getListPinsReturnsItemsAndNextCursor() throws Exception {
		when(pinService.getListPins(any(AuthenticatedUser.class), any(PinListRequest.class)))
			.thenReturn(new CursorPage<>(List.of(pinItem()), "MTA="));

		mockMvc.perform(get("/api/v1/pins/list")
				.with(authenticated())
				.param("type", "meeting")
				.param("size", "20"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nextCursor", is("MTA=")))
			.andExpect(jsonPath("$.items[0].title", is("coffee")));
	}

	@Test
	void getMapPinsRejectsMissingRequiredParameter() throws Exception {
		mockMvc.perform(get("/api/v1/pins")
				.with(authenticated())
				.param("swLng", "126.0")
				.param("neLat", "38.0")
				.param("neLng", "128.0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));

		verify(pinService, never()).getMapPins(any(AuthenticatedUser.class), any(PinMapRequest.class));
	}

	@Test
	void getListPinsMapsInvalidCursorError() throws Exception {
		when(pinService.getListPins(any(AuthenticatedUser.class), any(PinListRequest.class)))
			.thenThrow(new InvalidPinRequestException("INVALID_CURSOR", "cursor", "Invalid cursor"));

		mockMvc.perform(get("/api/v1/pins/list")
				.with(authenticated())
				.param("cursor", "bad"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("INVALID_CURSOR")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("cursor")));
	}

	private static RequestPostProcessor authenticated() {
		return request -> {
			AuthenticatedUser principal = new AuthenticatedUser(
				42L,
				"user@example.com",
				UserRole.user,
				UserStatus.active
			);
			SecurityContextHolder.getContext()
				.setAuthentication(new TestingAuthenticationToken(principal, null));
			return request;
		};
	}

	private static PinItem pinItem() {
		return new PinItem(
			10L,
			PinType.meeting,
			"coffee",
			"/api/v1/files/file-id?v=thumb",
			new PinLocation(37.55, 126.98),
			true,
			OffsetDateTime.parse("2026-07-08T10:00:00+09:00")
		);
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		PinService pinService() {
			return mock(PinService.class);
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
