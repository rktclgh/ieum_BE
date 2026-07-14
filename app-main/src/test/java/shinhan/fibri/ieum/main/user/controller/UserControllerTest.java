package shinhan.fibri.ieum.main.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import shinhan.fibri.ieum.main.auth.session.AuthCookieWriter;
import shinhan.fibri.ieum.main.auth.session.AuthSessionProperties;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.user.dto.ProfileImageResponse;
import shinhan.fibri.ieum.main.user.dto.UpdateProfileImageRequest;
import shinhan.fibri.ieum.main.user.dto.UpdateUserLocationRequest;
import shinhan.fibri.ieum.main.user.dto.UpdateUserProfileRequest;
import shinhan.fibri.ieum.main.user.dto.UpdateUserSettingsRequest;
import shinhan.fibri.ieum.main.user.dto.UserMeResponse;
import shinhan.fibri.ieum.main.user.dto.UserSettingsResponse;
import shinhan.fibri.ieum.main.user.service.UserService;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserService userService;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
		reset(userService);
	}

	@Test
	void getMeReturnsCurrentUser() throws Exception {
		when(userService.getMe(any(AuthenticatedUser.class))).thenReturn(userResponse());

		mockMvc.perform(get("/api/v1/users/me").with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.userId", is(42)))
			.andExpect(jsonPath("$.email", is("user@example.com")))
			.andExpect(jsonPath("$.nickname", is("nickname")))
			.andExpect(jsonPath("$.grade", is("bronze")))
			.andExpect(jsonPath("$.acceptedCount", is(0)))
			.andExpect(jsonPath("$.profileImageUrl", is("/api/v1/files/11111111-1111-1111-1111-111111111111")))
			.andExpect(jsonPath("$.settings.notifyAll", is(true)));
	}

	@Test
	void updateMeReturnsUpdatedUser() throws Exception {
		when(userService.updateMe(any(AuthenticatedUser.class), any(UpdateUserProfileRequest.class)))
			.thenReturn(userResponse());

		mockMvc.perform(patch("/api/v1/users/me")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "nickname": "nickname",
					  "nationality": "KR"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.settings.language", is("ko")));
	}

	@Test
	void updateMeRejectsBlankNickname() throws Exception {
		mockMvc.perform(patch("/api/v1/users/me")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "nickname": "  "
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));

		verify(userService, never()).updateMe(any(AuthenticatedUser.class), any(UpdateUserProfileRequest.class));
	}

	@Test
	void updateMeRejectsProfanityNickname() throws Exception {
		mockMvc.perform(patch("/api/v1/users/me")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "nickname": "씨발"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));

		verify(userService, never()).updateMe(any(AuthenticatedUser.class), any(UpdateUserProfileRequest.class));
	}

	@Test
	void updateMeRejectsFutureBirthDate() throws Exception {
		mockMvc.perform(patch("/api/v1/users/me")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "birthDate": "2999-01-01"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));

		verify(userService, never()).updateMe(any(AuthenticatedUser.class), any(UpdateUserProfileRequest.class));
	}

	@Test
	void updateSettingsReturnsUpdatedSettings() throws Exception {
		when(userService.updateSettings(any(AuthenticatedUser.class), any(UpdateUserSettingsRequest.class)))
			.thenReturn(settingsResponse());

		mockMvc.perform(patch("/api/v1/users/me/settings")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "cameraPermission": true,
					  "notifyAll": true,
					  "notifyRadiusKm": 10
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.notifyAll", is(true)))
			.andExpect(jsonPath("$.notifyRadiusKm", is(5)));
	}

	@Test
	void updateLocationReturnsNoContent() throws Exception {
		mockMvc.perform(put("/api/v1/users/me/location")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "longitude": 127.0276,
					  "latitude": 37.4979
					}
					"""))
			.andExpect(status().isNoContent());

		verify(userService).updateLocation(any(AuthenticatedUser.class), any(UpdateUserLocationRequest.class));
	}

	@Test
	void updateProfileImageReturnsProfileImageUrl() throws Exception {
		UUID fileId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		when(userService.updateProfileImage(any(AuthenticatedUser.class), any(UpdateProfileImageRequest.class)))
			.thenReturn(new ProfileImageResponse("/api/v1/files/22222222-2222-2222-2222-222222222222"));

		mockMvc.perform(put("/api/v1/users/me/profile-image")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "fileId": "22222222-2222-2222-2222-222222222222"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.profileImageUrl", is("/api/v1/files/22222222-2222-2222-2222-222222222222")));

		verify(userService).updateProfileImage(any(AuthenticatedUser.class), any(UpdateProfileImageRequest.class));
	}

	@Test
	void deleteProfileImageReturnsNoContent() throws Exception {
		mockMvc.perform(delete("/api/v1/users/me/profile-image").with(authenticated()))
			.andExpect(status().isNoContent());

		verify(userService).deleteProfileImage(any(AuthenticatedUser.class));
	}

	@Test
	void withdrawExpiresAuthCookies() throws Exception {
		mockMvc.perform(delete("/api/v1/users/me").with(authenticated()))
			.andExpect(status().isNoContent())
			.andExpect(result -> assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
				.anySatisfy(cookie -> assertThat(cookie).contains("access_token=").contains("Max-Age=0"))
				.anySatisfy(cookie -> assertThat(cookie).contains("refresh_token=").contains("Max-Age=0"))
				.anySatisfy(cookie -> assertThat(cookie).contains("csrf_token=").contains("Max-Age=0")));

		verify(userService).withdraw(any(AuthenticatedUser.class));
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

	private UserMeResponse userResponse() {
		return new UserMeResponse(
			42L,
			"user@example.com",
			"nickname",
			LocalDate.of(1995, 5, 20),
			"female",
			"KR",
			"bronze",
			0,
			"/api/v1/files/11111111-1111-1111-1111-111111111111",
			settingsResponse()
		);
	}

	private UserSettingsResponse settingsResponse() {
		return new UserSettingsResponse("ko", false, true, true, true, true, 5);
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		UserService userService() {
			return mock(UserService.class);
		}

		@Bean
		@Primary
		SessionTokenValidator sessionTokenValidator() {
			return mock(SessionTokenValidator.class);
		}

		@Bean
		@Primary
		AuthCookieWriter authCookieWriter() {
			return new AuthCookieWriter(new AuthSessionProperties(true, "Lax", "", 1800, 1209600));
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
