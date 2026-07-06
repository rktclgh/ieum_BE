package shinhan.fibri.ieum.main.user.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.user.dto.UpdateUserProfileRequest;
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
