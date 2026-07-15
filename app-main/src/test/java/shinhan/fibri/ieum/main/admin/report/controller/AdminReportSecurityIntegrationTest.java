package shinhan.fibri.ieum.main.admin.report.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportListResponse;
import shinhan.fibri.ieum.main.admin.report.service.AdminReportDecisionService;
import shinhan.fibri.ieum.main.admin.report.service.AdminReportDetailService;
import shinhan.fibri.ieum.main.admin.report.service.AdminReportQueryService;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.auth.session.ValidatedAuthSession;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

@SpringBootTest
@AutoConfigureMockMvc
class AdminReportSecurityIntegrationTest {

	@DynamicPropertySource
	static void configureDataSource(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, "admin_report_security");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SessionTokenValidator sessionTokenValidator;

	@Autowired
	private AdminReportQueryService queryService;

	@Autowired
	private AdminReportDetailService detailService;

	@Autowired
	private AdminReportDecisionService decisionService;

	@BeforeEach
	void resetMocks() {
		reset(sessionTokenValidator, queryService, detailService, decisionService);
	}

	@Test
	void anonymousAdminReportGetReturnsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/admin/reports"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("AUTHENTICATION_REQUIRED")));
	}

	@Test
	void normalUserAdminReportGetReturnsForbidden() throws Exception {
		when(sessionTokenValidator.validateSession("user-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(user(), "user-session")));

		mockMvc.perform(get("/api/v1/admin/reports").cookie(new MockCookie("access_token", "user-token")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
	}

	@Test
	void adminAdminReportGetSucceeds() throws Exception {
		when(sessionTokenValidator.validateSession("admin-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(admin(), "admin-session")));
		when(queryService.getReports(any())).thenReturn(new AdminReportListResponse(List.of(), null));

		mockMvc.perform(get("/api/v1/admin/reports").cookie(new MockCookie("access_token", "admin-token")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray());
	}

	@Test
	void anonymousAdminDecisionWithCsrfStillReturnsUnauthorized() throws Exception {
		mockMvc.perform(post("/api/v1/admin/reports/10/confirm")
				.cookie(new MockCookie("csrf_token", "csrf-token"))
				.header("X-CSRF-Token", "csrf-token"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("AUTHENTICATION_REQUIRED")));
	}

	@Test
	void adminDecisionWithoutCsrfReturnsCsrfFailed() throws Exception {
		when(sessionTokenValidator.validateSession("admin-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(admin(), "admin-session")));

		mockMvc.perform(post("/api/v1/admin/reports/10/confirm")
				.cookie(new MockCookie("access_token", "admin-token")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("CSRF_FAILED")));
	}

	@Test
	void adminDecisionWithMatchingCsrfSucceeds() throws Exception {
		when(sessionTokenValidator.validateSession("admin-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(admin(), "admin-session")));

		mockMvc.perform(post("/api/v1/admin/reports/10/confirm")
				.cookie(
					new MockCookie("access_token", "admin-token"),
					new MockCookie("csrf_token", "csrf-token")
				)
				.header("X-CSRF-Token", "csrf-token"))
			.andExpect(status().isNoContent());

		verify(decisionService).confirm(10L, 1L);
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
		AdminReportQueryService adminReportQueryService() {
			return mock(AdminReportQueryService.class);
		}

		@Bean
		@Primary
		AdminReportDetailService adminReportDetailService() {
			return mock(AdminReportDetailService.class);
		}

		@Bean
		@Primary
		AdminReportDecisionService adminReportDecisionService() {
			return mock(AdminReportDecisionService.class);
		}
	}
}
