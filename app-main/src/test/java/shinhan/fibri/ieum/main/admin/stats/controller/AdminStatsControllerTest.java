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
import shinhan.fibri.ieum.main.admin.stats.dto.AdminStatsDailySeriesResponse;
import shinhan.fibri.ieum.main.admin.stats.dto.AdminStatsOverviewResponse;
import shinhan.fibri.ieum.main.admin.stats.dto.AdminStatsQueueResponse;
import shinhan.fibri.ieum.main.admin.stats.dto.ContentStatsResponse;
import shinhan.fibri.ieum.main.admin.stats.dto.ReportStatsResponse;
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
	void contentEndpointReturnsContentStats() throws Exception {
		when(queryService.getContentStats(any())).thenReturn(new ContentStatsResponse(
			LocalDate.of(2026, 7, 1),
			LocalDate.of(2026, 7, 31),
			3,
			4,
			5,
			10,
			0.4,
			20
		));

		mockMvc.perform(get("/api/v1/admin/stats/content").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.pinCount", is(3)))
			.andExpect(jsonPath("$.questionCount", is(4)))
			.andExpect(jsonPath("$.meetingCount", is(5)))
			.andExpect(jsonPath("$.answerCount", is(10)))
			.andExpect(jsonPath("$.acceptedRate", is(0.4)))
			.andExpect(jsonPath("$.messageCount", is(20)));
	}

	@Test
	void reportsEndpointReturnsReportStats() throws Exception {
		when(queryService.getReportStats(any())).thenReturn(new ReportStatsResponse(
			LocalDate.of(2026, 7, 1),
			LocalDate.of(2026, 7, 31),
			11,
			9,
			7,
			3,
			6
		));

		mockMvc.perform(get("/api/v1/admin/stats/reports").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reportCount", is(11)))
			.andExpect(jsonPath("$.aiReviewedCount", is(9)))
			.andExpect(jsonPath("$.confirmedCount", is(7)))
			.andExpect(jsonPath("$.dismissedCount", is(3)))
			.andExpect(jsonPath("$.sanctionCount", is(6)));
	}

	@Test
	void overviewEndpointReturnsSummarySeriesAndQueues() throws Exception {
		when(queryService.getOverview(any())).thenReturn(new AdminStatsOverviewResponse(
			LocalDate.of(2026, 7, 1),
			LocalDate.of(2026, 7, 3),
			"day",
			new AdminStatsOverviewResponse.Summary(3, 2, 1, 4, 10, 4, 0.4, 6, 7, 8, 9, 5),
			java.util.List.of(new AdminStatsDailySeriesResponse(
				LocalDate.of(2026, 7, 1),
				1,
				2,
				3,
				4,
				1,
				5,
				6,
				7,
				8,
				9
			)),
			new AdminStatsQueueResponse(11, 12, 13, 14)
		));

		mockMvc.perform(get("/api/v1/admin/stats/overview")
				.with(admin())
				.param("from", "2026-07-01")
				.param("to", "2026-07-03")
				.param("bucket", "day"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.bucket", is("day")))
			.andExpect(jsonPath("$.summary.acceptedRate", is(0.4)))
			.andExpect(jsonPath("$.summary.humanAnswerCount", is(10)))
			.andExpect(jsonPath("$.series[0].date", is("2026-07-01")))
			.andExpect(jsonPath("$.queues.pendingReportCount", is(11)));
	}

	@Test
	void invalidDateFormatMapsToValidationFailure() throws Exception {
		mockMvc.perform(get("/api/v1/admin/stats/users")
				.with(admin())
				.param("from", "2026/07/01"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("from")))
			// 스프링 내부 변환 메시지 대신 정제된 메시지로 응답해야 한다
			.andExpect(jsonPath("$.fieldErrors[0].message", is("Invalid value")));
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
