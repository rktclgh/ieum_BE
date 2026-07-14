package shinhan.fibri.ieum.main.admin.stats.controller;

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
import shinhan.fibri.ieum.main.admin.stats.service.AdminStatsQueryService;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

@SpringBootTest
@AutoConfigureMockMvc
class AdminStatsSecurityIntegrationTest {

	@DynamicPropertySource
	static void configureDataSource(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, "admin_stats_security");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SessionTokenValidator sessionTokenValidator;

	@Autowired
	private AdminStatsQueryService queryService;

	@BeforeEach
	void resetMocks() {
		reset(sessionTokenValidator, queryService);
	}

	@Test
	void normalUserAdminStatsGetReturnsForbidden() throws Exception {
		when(sessionTokenValidator.validate("user-token")).thenReturn(Optional.of(user()));

		mockMvc.perform(get("/api/v1/admin/stats/users").cookie(new MockCookie("access_token", "user-token")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("ACCESS_DENIED")));

		verify(queryService, never()).getUserStats(any());
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
		AdminStatsQueryService adminStatsQueryService() {
			return mock(AdminStatsQueryService.class);
		}
	}
}
