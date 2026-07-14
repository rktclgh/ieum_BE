package shinhan.fibri.ieum.main.admin.stats.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.stats.dto.UserStatsResponse;
import shinhan.fibri.ieum.main.admin.stats.exception.InvalidStatsRangeException;
import shinhan.fibri.ieum.main.admin.stats.service.AdminStatsQueryService;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;

@WebMvcTest(AdminStatsController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminStatsControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AdminStatsQueryService queryService;

	@AfterEach
	void resetMocks() {
		SecurityContextHolder.clearContext();
		reset(queryService);
	}

	@Test
	void usersEndpointReturnsUserStatsAndBindsDateRange() throws Exception {
		when(queryService.getUserStats(any())).thenReturn(new UserStatsResponse(
			LocalDate.of(2026, 7, 1),
			LocalDate.of(2026, 7, 31),
			10,
			8,
			2
		));

		mockMvc.perform(get("/api/v1/admin/stats/users")
				.with(admin())
				.param("from", "2026-07-01")
				.param("to", "2026-07-31"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.from", is("2026-07-01")))
			.andExpect(jsonPath("$.to", is("2026-07-31")))
			.andExpect(jsonPath("$.signupCount", is(10)))
			.andExpect(jsonPath("$.activeUserCount", is(8)))
			.andExpect(jsonPath("$.suspendedUserCount", is(2)));

		verify(queryService).getUserStats(argThat(request ->
			request.from().equals(LocalDate.of(2026, 7, 1))
				&& request.to().equals(LocalDate.of(2026, 7, 31))
		));
	}

	@Test
	void invalidRangeMapsToDedicatedBadRequestCode() throws Exception {
		when(queryService.getUserStats(any())).thenThrow(new InvalidStatsRangeException("from must be before or equal to to"));

		mockMvc.perform(get("/api/v1/admin/stats/users")
				.with(admin())
				.param("from", "2026-07-31")
				.param("to", "2026-07-01"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("INVALID_STATS_RANGE")))
			.andExpect(jsonPath("$.message", is("from must be before or equal to to")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("range")));
	}

	@Test
	void invalidDateFormatMapsToValidationFailure() throws Exception {
		mockMvc.perform(get("/api/v1/admin/stats/users")
				.with(admin())
				.param("from", "2026/07/01"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("from")));
	}

	private static RequestPostProcessor admin() {
		return request -> {
			AuthenticatedUser principal = new AuthenticatedUser(
				1L,
				"admin@example.com",
				UserRole.admin,
				UserStatus.active
			);
			SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal, null));
			return request;
		};
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		AdminStatsQueryService adminStatsQueryService() {
			return mock(AdminStatsQueryService.class);
		}

		@Bean
		@Primary
		SessionTokenValidator sessionTokenValidator() {
			return mock(SessionTokenValidator.class);
		}
	}
}
