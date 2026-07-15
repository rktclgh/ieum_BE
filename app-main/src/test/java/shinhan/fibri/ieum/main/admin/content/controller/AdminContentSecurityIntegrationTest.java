package shinhan.fibri.ieum.main.admin.content.controller;

import static org.hamcrest.Matchers.is;
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
import org.springframework.mock.web.MockCookie;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.content.service.AdminContentService;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.auth.session.ValidatedAuthSession;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

@SpringBootTest
@AutoConfigureMockMvc
class AdminContentSecurityIntegrationTest {

	@DynamicPropertySource
	static void configureDataSource(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, "admin_content_security");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SessionTokenValidator sessionTokenValidator;

	@Autowired
	private AdminContentService adminContentService;

	@BeforeEach
	void resetMocks() {
		reset(sessionTokenValidator, adminContentService);
	}

	@Test
	void normalUserAdminContentDeleteReturnsForbidden() throws Exception {
		when(sessionTokenValidator.validateSession("user-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(user(), "user-session")));

		mockMvc.perform(delete("/api/v1/admin/content/question/200")
				.cookie(
					new MockCookie("access_token", "user-token"),
					new MockCookie("csrf_token", "csrf-token")
				)
				.header("X-CSRF-Token", "csrf-token"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("ACCESS_DENIED")));

		verifyNoInteractions(adminContentService);
	}

	@Test
	void adminContentDeleteWithoutCsrfReturnsCsrfFailed() throws Exception {
		when(sessionTokenValidator.validateSession("admin-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(admin(), "admin-session")));

		mockMvc.perform(delete("/api/v1/admin/content/question/200")
				.cookie(new MockCookie("access_token", "admin-token")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("CSRF_FAILED")));

		verifyNoInteractions(adminContentService);
	}

	@Test
	void adminContentDeleteWithMatchingCsrfSucceeds() throws Exception {
		when(sessionTokenValidator.validateSession("admin-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(admin(), "admin-session")));

		mockMvc.perform(delete("/api/v1/admin/content/question/200")
				.cookie(
					new MockCookie("access_token", "admin-token"),
					new MockCookie("csrf_token", "csrf-token")
				)
				.header("X-CSRF-Token", "csrf-token"))
			.andExpect(status().isNoContent());

		verify(adminContentService).hide("question", 200L);
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
		AdminContentService adminContentService() {
			return mock(AdminContentService.class);
		}
	}
}
