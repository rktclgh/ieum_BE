package shinhan.fibri.ieum.main.admin.user.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.user.service.AdminUserHardDeleteService;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.auth.session.ValidatedAuthSession;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

@SpringBootTest
@AutoConfigureMockMvc
class AdminUserHardDeleteSecurityIntegrationTest {

	@DynamicPropertySource
	static void configureDataSource(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, "admin_user_hard_delete_security");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SessionTokenValidator sessionTokenValidator;

	@Autowired
	private AdminUserHardDeleteService hardDeleteService;

	@BeforeEach
	void resetMocks() {
		reset(sessionTokenValidator, hardDeleteService);
	}

	@Test
	void normalUserHardDeleteReturnsForbidden() throws Exception {
		when(sessionTokenValidator.validateSession("user-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(user(), "user-session")));

		mockMvc.perform(delete("/api/v1/admin/users/10")
				.cookie(
					new MockCookie("access_token", "user-token"),
					new MockCookie("csrf_token", "csrf-token")
				)
				.header("X-CSRF-Token", "csrf-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"confirmationEmail":"user@example.com"}
					"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("ACCESS_DENIED")));

		verifyNoInteractions(hardDeleteService);
	}

	@Test
	void adminHardDeleteWithoutCsrfReturnsCsrfFailed() throws Exception {
		when(sessionTokenValidator.validateSession("admin-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(admin(), "admin-session")));

		mockMvc.perform(delete("/api/v1/admin/users/10")
				.cookie(new MockCookie("access_token", "admin-token"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"confirmationEmail":"user@example.com"}
					"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("CSRF_FAILED")));

		verifyNoInteractions(hardDeleteService);
	}

	@Test
	void adminHardDeleteWithMatchingCsrfSucceeds() throws Exception {
		when(sessionTokenValidator.validateSession("admin-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(admin(), "admin-session")));

		mockMvc.perform(delete("/api/v1/admin/users/10")
				.cookie(
					new MockCookie("access_token", "admin-token"),
					new MockCookie("csrf_token", "csrf-token")
				)
				.header("X-CSRF-Token", "csrf-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"confirmationEmail":"user@example.com"}
					"""))
			.andExpect(status().isNoContent());

		verify(hardDeleteService).hardDelete(any(AuthenticatedUser.class), eq(10L), eq("user@example.com"));
	}

	private AuthenticatedUser admin() {
		return new AuthenticatedUser(1L, "admin@example.com", UserRole.admin, UserStatus.active);
	}

	private AuthenticatedUser user() {
		return new AuthenticatedUser(2L, "user@example.com", UserRole.user, UserStatus.active);
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		SessionTokenValidator sessionTokenValidator() {
			return mock(SessionTokenValidator.class);
		}

		@Bean
		@Primary
		AdminUserHardDeleteService adminUserHardDeleteService() {
			return mock(AdminUserHardDeleteService.class);
		}
	}
}
