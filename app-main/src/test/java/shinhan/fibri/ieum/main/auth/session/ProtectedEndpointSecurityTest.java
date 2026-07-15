package shinhan.fibri.ieum.main.auth.session;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

@SpringBootTest
@AutoConfigureMockMvc
class ProtectedEndpointSecurityTest {

	@DynamicPropertySource
	static void configureDataSource(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, "protected_security");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SessionTokenValidator sessionTokenValidator;

	@Test
	void frontendGetAllowsAnonymousRequest() throws Exception {
		mockMvc.perform(get("/static-security/ping"))
			.andExpect(status().isOk());
	}

	@Test
	void frontendHeadAllowsAnonymousRequest() throws Exception {
		mockMvc.perform(head("/static-security/ping"))
			.andExpect(status().isOk());
	}

	@Test
	void frontendUnsafeMethodStillRequiresAuthentication() throws Exception {
		mockMvc.perform(post("/static-security/ping"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("AUTHENTICATION_REQUIRED")));
	}

	@Test
	void protectedApiHeadStillRequiresAuthentication() throws Exception {
		mockMvc.perform(head("/api/v1/protected/ping"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("AUTHENTICATION_REQUIRED")));
	}

	@Test
	void nonPublicActuatorEndpointStillRequiresAuthentication() throws Exception {
		mockMvc.perform(get("/actuator/info"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("AUTHENTICATION_REQUIRED")));
	}

	@Test
	void defaultSpringSecurityLogoutEndpointIsDisabled() throws Exception {
		mockMvc.perform(get("/logout"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
			.andExpect(content().string(containsString("NEXT_STATIC_404")))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
			.andExpect(header().string(
				"Cross-Origin-Opener-Policy",
				"same-origin-allow-popups"
			));
	}

	@Test
	void protectedEndpointReturnsJsonUnauthorizedWhenAccessCookieIsMissing() throws Exception {
		mockMvc.perform(get("/api/v1/protected/ping"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("AUTHENTICATION_REQUIRED")))
			.andExpect(jsonPath("$.message", is("Authentication is required")));
	}

	@Test
	void pinsEndpointReturnsJsonUnauthorizedWhenAccessCookieIsMissing() throws Exception {
		mockMvc.perform(get("/api/v1/pins")
				.param("swLat", "37.0")
				.param("swLng", "126.0")
				.param("neLat", "38.0")
				.param("neLng", "128.0"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("AUTHENTICATION_REQUIRED")))
			.andExpect(jsonPath("$.message", is("Authentication is required")));
	}

	@Test
	void answerReportEndpointReturnsJsonUnauthorizedWhenAccessCookieIsMissing() throws Exception {
		mockMvc.perform(post("/api/v1/answers/300/report")
				.cookie(new MockCookie("csrf_token", "csrf-token"))
				.header("X-CSRF-Token", "csrf-token")
				.contentType("application/json")
				.content("{\"reason\":\"spam\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("AUTHENTICATION_REQUIRED")))
			.andExpect(jsonPath("$.message", is("Authentication is required")));
	}

	@Test
	void questionRoomEndpointReturnsJsonUnauthorizedWhenAccessCookieIsMissing() throws Exception {
		mockMvc.perform(post("/api/v1/chat/rooms/question")
				.contentType("application/json")
				.content("{\"questionId\":9,\"targetUserId\":77}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("AUTHENTICATION_REQUIRED")))
			.andExpect(jsonPath("$.message", is("Authentication is required")));
	}

	@Test
	void questionRoomEndpointReturnsJsonForbiddenWhenAuthenticatedWithoutCsrfToken() throws Exception {
		when(sessionTokenValidator.validateSession("user-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(new AuthenticatedUser(
				42L,
				"user@example.com",
				UserRole.user,
				UserStatus.active
			), "user-session")));

		mockMvc.perform(post("/api/v1/chat/rooms/question")
				.cookie(new MockCookie("access_token", "user-token"))
				.contentType("application/json")
				.content("{\"questionId\":9,\"targetUserId\":77}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("CSRF_FAILED")))
			.andExpect(jsonPath("$.message", is("CSRF validation failed")));
	}

	@Test
	void webPushSubscriptionPutRequiresCsrf() throws Exception {
		stubUserSession();

		mockMvc.perform(put("/api/v1/notifications/push/subscription")
				.cookie(new MockCookie("access_token", "user-token"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("CSRF_FAILED")));
	}

	@Test
	void webPushSubscriptionDeleteRequiresCsrf() throws Exception {
		stubUserSession();

		mockMvc.perform(delete("/api/v1/notifications/push/subscription")
				.cookie(new MockCookie("access_token", "user-token")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("CSRF_FAILED")));
	}

	@Test
	void refreshEndpointStillRequiresCsrfWithoutAccessAuthentication() throws Exception {
		mockMvc.perform(post("/api/v1/auth/refresh"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("CSRF_FAILED")))
			.andExpect(jsonPath("$.message", is("CSRF validation failed")));
	}

	@Test
	void adminEndpointReturnsJsonForbiddenForUserRole() throws Exception {
		when(sessionTokenValidator.validateSession("user-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(new AuthenticatedUser(
				42L,
				"user@example.com",
				UserRole.user,
				UserStatus.active
			), "user-session")));

		mockMvc.perform(get("/api/v1/admin/ping")
				.cookie(new MockCookie("access_token", "user-token")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("ACCESS_DENIED")))
			.andExpect(jsonPath("$.message", is("Access is denied")));
	}

	@Test
	void adminEndpointAllowsAdminRole() throws Exception {
		when(sessionTokenValidator.validateSession("admin-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(new AuthenticatedUser(
				1L,
				"admin@example.com",
				UserRole.admin,
				UserStatus.active
			), "admin-session")));

		mockMvc.perform(get("/api/v1/admin/ping")
				.cookie(new MockCookie("access_token", "admin-token")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", is("admin-ok")));
	}

	@Test
	void suspendedUserInquiryAllowsAnonymousRequestWithoutCsrfToken() throws Exception {
		mockMvc.perform(post("/api/v1/inquiries/suspended-users")
				.contentType("application/json")
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
	}

	@Test
	void adminLoginEndpointDoesNotExist() throws Exception {
		when(sessionTokenValidator.validateSession("admin-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(new AuthenticatedUser(
				1L,
				"admin@example.com",
				UserRole.admin,
				UserStatus.active
			), "admin-session")));

		mockMvc.perform(post("/api/v1/admin/login")
				.cookie(new MockCookie("access_token", "admin-token"))
				.cookie(new MockCookie("csrf_token", "csrf-token"))
				.header("X-CSRF-Token", "csrf-token")
				.contentType("application/json")
				.content("{}"))
			.andExpect(status().isNotFound());
	}

	private void stubUserSession() {
		when(sessionTokenValidator.validateSession("user-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(new AuthenticatedUser(
				42L,
				"user@example.com",
				UserRole.user,
				UserStatus.active
			), "user-session")));
	}

	@RestController
	static class ProtectedController {

		@GetMapping("/static-security/ping")
		String staticPing() {
			return "static-ok";
		}

		@GetMapping("/api/v1/protected/ping")
		String ping() {
			return "ok";
		}

		@GetMapping("/api/v1/admin/ping")
		String adminPing() {
			return "admin-ok";
		}
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		SessionTokenValidator sessionTokenValidator() {
			return mock(SessionTokenValidator.class);
		}

		@Bean
		ProtectedController protectedController() {
			return new ProtectedController();
		}
	}
}
