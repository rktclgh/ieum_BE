package shinhan.fibri.ieum.main.admin.report.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportAiDetail;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportAiSummary;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportDetailResponse;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportListItem;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportListResponse;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportTargetSummary;
import shinhan.fibri.ieum.main.admin.report.dto.AdminReportUserSummary;
import shinhan.fibri.ieum.main.admin.report.exception.AdminReportAlreadyResolvedException;
import shinhan.fibri.ieum.main.admin.report.exception.AdminReportNotFoundException;
import shinhan.fibri.ieum.main.admin.report.exception.InvalidAdminReportCursorException;
import shinhan.fibri.ieum.main.admin.report.service.AdminReportDecisionService;
import shinhan.fibri.ieum.main.admin.report.service.AdminReportDetailService;
import shinhan.fibri.ieum.main.admin.report.service.AdminReportQueryService;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.report.domain.ReportAiReviewState;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.main.report.domain.ReportStatus;
import shinhan.fibri.ieum.main.report.domain.ReportTargetType;
import tools.jackson.databind.node.JsonNodeFactory;

@WebMvcTest(AdminReportController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminReportControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AdminReportQueryService queryService;

	@Autowired
	private AdminReportDetailService detailService;

	@Autowired
	private AdminReportDecisionService decisionService;

	@AfterEach
	void resetMocks() {
		SecurityContextHolder.clearContext();
		reset(queryService, detailService, decisionService);
	}

	@Test
	void listReturnsFilteredCursorPage() throws Exception {
		when(queryService.getReports(any())).thenReturn(new AdminReportListResponse(List.of(listItem()), "next"));

		mockMvc.perform(get("/api/v1/admin/reports")
				.with(admin())
				.param("status", "pending")
				.param("aiReviewState", "completed")
				.param("decision", "suspend")
				.param("cursor", "cursor")
				.param("size", "20"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nextCursor", is("next")))
			.andExpect(jsonPath("$.items[0].reportId", is(10)))
			.andExpect(jsonPath("$.items[0].target.type", is("message")))
			.andExpect(jsonPath("$.items[0].ai.reviewState", is("completed")));

		verify(queryService).getReports(argThat(request ->
			request.status() == ReportStatus.pending
				&& request.aiReviewState() == ReportAiReviewState.completed
				&& request.decision().name().equals("suspend")
				&& request.cursor().equals("cursor")
				&& request.size() == 20
		));
	}

	@Test
	void detailReturnsSanitizedResponseShape() throws Exception {
		when(detailService.getReport(10L)).thenReturn(detail());

		mockMvc.perform(get("/api/v1/admin/reports/10").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reportId", is(10)))
			.andExpect(jsonPath("$.contextSnapshot.reported.messageId", is(1000)))
			.andExpect(jsonPath("$.ai.result.category", is("abuse")))
			.andExpect(jsonPath("$.sanctions").isArray());
	}

	@Test
	void confirmAndDismissAreBodylessNoContentActions() throws Exception {
		mockMvc.perform(post("/api/v1/admin/reports/10/confirm").with(admin()))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));
		mockMvc.perform(post("/api/v1/admin/reports/11/dismiss").with(admin()))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		verify(decisionService).confirm(10L, 1L);
		verify(decisionService).dismiss(11L, 1L);
	}

	@ParameterizedTest
	@CsvSource({"status,unknown", "aiReviewState,unknown", "decision,unknown"})
	void invalidEnumFilterReturnsValidationFailure(String field, String value) throws Exception {
		mockMvc.perform(get("/api/v1/admin/reports").with(admin()).param(field, value))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is(field)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "51"})
	void sizeOutsideOneToFiftyReturnsValidationFailure(String size) throws Exception {
		mockMvc.perform(get("/api/v1/admin/reports").with(admin()).param("size", size))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("size")));
	}

	@Test
	void invalidOpaqueCursorReturnsDedicatedError() throws Exception {
		when(queryService.getReports(any())).thenThrow(new InvalidAdminReportCursorException());

		mockMvc.perform(get("/api/v1/admin/reports").with(admin()).param("cursor", "bad"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("INVALID_CURSOR")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("cursor")));
	}

	@Test
	void missingReportReturnsReportNotFound() throws Exception {
		when(detailService.getReport(999L)).thenThrow(new AdminReportNotFoundException());

		mockMvc.perform(get("/api/v1/admin/reports/999").with(admin()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("REPORT_NOT_FOUND")));
	}

	@Test
	void oppositeFinalDecisionReturnsReportAlreadyResolved() throws Exception {
		doThrow(new AdminReportAlreadyResolvedException()).when(decisionService).confirm(10L, 1L);

		mockMvc.perform(post("/api/v1/admin/reports/10/confirm").with(admin()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("REPORT_ALREADY_RESOLVED")));
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

	private AdminReportListItem listItem() {
		return new AdminReportListItem(
			10L,
			new AdminReportTargetSummary(ReportTargetType.message, 1000L, false),
			new AdminReportUserSummary(1L, "reporter"),
			new AdminReportUserSummary(2L, "reported"),
			ReportReason.abuse,
			ReportStatus.pending,
			new AdminReportAiSummary(ReportAiReviewState.completed, null, null, null, null),
			OffsetDateTime.parse("2026-07-14T10:00:00Z")
		);
	}

	private AdminReportDetailResponse detail() {
		var snapshot = JsonNodeFactory.instance.objectNode();
		snapshot.putObject("reported").put("messageId", 1000L);
		var result = JsonNodeFactory.instance.objectNode().put("category", "abuse");
		return new AdminReportDetailResponse(
			10L,
			new AdminReportTargetSummary(ReportTargetType.message, 1000L, false),
			new AdminReportUserSummary(1L, "reporter"),
			new AdminReportUserSummary(2L, "reported"),
			ReportReason.abuse,
			"detail",
			ReportStatus.pending,
			snapshot,
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			new AdminReportAiDetail(
				ReportAiReviewState.completed,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				result,
				null
			),
			null,
			List.of(),
			OffsetDateTime.parse("2026-07-14T10:00:00Z")
		);
	}

	@TestConfiguration
	static class TestConfig {

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

		@Bean
		@Primary
		SessionTokenValidator sessionTokenValidator() {
			return mock(SessionTokenValidator.class);
		}

		@Bean
		WebMvcConfigurer authenticationPrincipalArgumentResolverConfigurer() {
			return new WebMvcConfigurer() {
				@Override
				public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
					resolvers.add(new AuthenticationPrincipalArgumentResolver());
				}
			};
		}
	}
}
