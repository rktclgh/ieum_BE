package shinhan.fibri.ieum.main.user.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import shinhan.fibri.ieum.main.user.dto.PublicUserProfileResponse;
import shinhan.fibri.ieum.main.user.dto.UserSearchResponse;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;
import shinhan.fibri.ieum.main.user.service.UserService;

@WebMvcTest(PublicUserController.class)
@AutoConfigureMockMvc(addFilters = false)
class PublicUserControllerTest {

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
	void searchUsersReturnsJsonArrayAndCallsService() throws Exception {
		when(userService.searchUsers(any(AuthenticatedUser.class), eq("nick")))
			.thenReturn(List.of(new UserSearchResponse(
				7L,
				"nickname",
				"KR",
				"/api/v1/files/11111111-1111-1111-1111-111111111111",
				true,
				OffsetDateTime.parse("2026-07-07T01:00:00Z")
			)));

		mockMvc.perform(get("/api/v1/users/search")
				.param("nickname", "nick")
				.with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].userId", is(7)))
			.andExpect(jsonPath("$[0].nickname", is("nickname")))
			.andExpect(jsonPath("$[0].nationality", is("KR")))
			.andExpect(jsonPath("$[0].profileImageUrl", is("/api/v1/files/11111111-1111-1111-1111-111111111111")))
			.andExpect(jsonPath("$[0].isFriend", is(true)))
			.andExpect(jsonPath("$[0].lastActiveAt", is("2026-07-07T01:00:00Z")));

		verify(userService).searchUsers(any(AuthenticatedUser.class), eq("nick"));
	}

	@Test
	void getPublicProfileReturnsJsonObject() throws Exception {
		when(userService.getPublicProfile(any(AuthenticatedUser.class), eq(7L)))
			.thenReturn(new PublicUserProfileResponse(
				7L,
				"nickname",
				"KR",
				null,
				"bronze",
				false,
				OffsetDateTime.parse("2026-07-07T01:00:00Z")
			));

		mockMvc.perform(get("/api/v1/users/{userId}", 7L).with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.userId", is(7)))
			.andExpect(jsonPath("$.nickname", is("nickname")))
			.andExpect(jsonPath("$.nationality", is("KR")))
			.andExpect(jsonPath("$.grade", is("bronze")))
			.andExpect(jsonPath("$.isFriend", is(false)))
			.andExpect(jsonPath("$.lastActiveAt", is("2026-07-07T01:00:00Z")));

		verify(userService).getPublicProfile(any(AuthenticatedUser.class), eq(7L));
	}

	@Test
	void illegalArgumentMapsToValidationFailed() throws Exception {
		when(userService.searchUsers(any(AuthenticatedUser.class), eq("")))
			.thenThrow(new IllegalArgumentException("nickname is required"));

		mockMvc.perform(get("/api/v1/users/search")
				.param("nickname", "")
				.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.message", is("nickname is required")));
	}

	@Test
	void userNotFoundMapsToUserNotFound() throws Exception {
		when(userService.getPublicProfile(any(AuthenticatedUser.class), eq(7L)))
			.thenThrow(new UserNotFoundException());

		mockMvc.perform(get("/api/v1/users/{userId}", 7L).with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("USER_NOT_FOUND")));
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
