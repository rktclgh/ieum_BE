package shinhan.fibri.ieum.main.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.main.admin.dto.AdminLoginResponse;
import shinhan.fibri.ieum.main.admin.service.AdminLoginResult;
import shinhan.fibri.ieum.main.admin.service.AdminLoginService;
import shinhan.fibri.ieum.main.auth.dto.LoginRequest;
import shinhan.fibri.ieum.main.auth.exception.EmailNotVerifiedException;
import shinhan.fibri.ieum.main.auth.exception.InvalidCredentialsException;
import shinhan.fibri.ieum.main.auth.exception.SuspendedUserException;
import shinhan.fibri.ieum.main.auth.session.AuthCookieWriter;
import shinhan.fibri.ieum.main.auth.session.AuthSessionProperties;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;

@WebMvcTest(AdminAuthController.class)
class AdminAuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AdminLoginService adminLoginService;

	@Test
	void loginReturnsAdminSummaryAndAuthCookiesWithoutBodyTokens() throws Exception {
		when(adminLoginService.login(any(LoginRequest.class))).thenReturn(new AdminLoginResult(
			new AdminLoginResponse(1L, UserRole.admin, false),
			"access-token",
			"refresh-token",
			"csrf-token"
		));

		mockMvc.perform(post("/api/v1/admin/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "admin@example.com",
					  "password": "Passw@rd123"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.userId", is(1)))
			.andExpect(jsonPath("$.role", is("admin")))
			.andExpect(jsonPath("$.passwordResetRequired", is(false)))
			.andExpect(jsonPath("$.accessToken").doesNotExist())
			.andExpect(jsonPath("$.refreshToken").doesNotExist())
			.andExpect(jsonPath("$.csrfToken").doesNotExist())
			.andExpect(result -> assertThat(result.getResponse().getContentAsString())
				.doesNotContain("access-token", "refresh-token", "csrf-token"))
			.andExpect(result -> assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
				.anySatisfy(cookie -> assertThat(cookie).contains("access_token=access-token"))
				.anySatisfy(cookie -> assertThat(cookie).contains("refresh_token=refresh-token"))
				.anySatisfy(cookie -> assertThat(cookie).contains("csrf_token=csrf-token")));
	}

	@Test
	void loginReturnsValidationFailedWhenEmailIsInvalid() throws Exception {
		mockMvc.perform(post("/api/v1/admin/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "not-an-email",
					  "password": "Passw@rd123"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("email")));
	}

	@Test
	void loginReturnsInvalidCredentialsWhenUserIsNotAdmin() throws Exception {
		doThrow(new InvalidCredentialsException())
			.when(adminLoginService)
			.login(any(LoginRequest.class));

		mockMvc.perform(post("/api/v1/admin/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "user@example.com",
					  "password": "Passw@rd123"
					}
					"""))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("INVALID_CREDENTIALS")));
	}

	@Test
	void loginReturnsForbiddenWhenEmailIsNotVerified() throws Exception {
		doThrow(new EmailNotVerifiedException())
			.when(adminLoginService)
			.login(any(LoginRequest.class));

		mockMvc.perform(post("/api/v1/admin/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "admin@example.com",
					  "password": "Passw@rd123"
					}
					"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("EMAIL_NOT_VERIFIED")));
	}

	@Test
	void loginReturnsForbiddenWhenUserIsSuspended() throws Exception {
		doThrow(new SuspendedUserException())
			.when(adminLoginService)
			.login(any(LoginRequest.class));

		mockMvc.perform(post("/api/v1/admin/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "admin@example.com",
					  "password": "Passw@rd123"
					}
					"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("SUSPENDED_USER")));
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		AdminLoginService adminLoginService() {
			return mock(AdminLoginService.class);
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
	}
}
