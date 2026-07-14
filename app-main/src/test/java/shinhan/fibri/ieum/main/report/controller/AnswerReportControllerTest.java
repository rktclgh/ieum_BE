package shinhan.fibri.ieum.main.report.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
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
import shinhan.fibri.ieum.main.answer.exception.AnswerNotFoundException;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.main.report.dto.CreateReportResponse;
import shinhan.fibri.ieum.main.report.service.ReportService;

@WebMvcTest(AnswerReportController.class)
@AutoConfigureMockMvc(addFilters = false)
class AnswerReportControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ReportService reportService;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
		reset(reportService);
	}

	@Test
	void createAnswerReportReturnsCreatedWithoutLocation() throws Exception {
		when(reportService.createAnswer(any(AuthenticatedUser.class), eq(300L), eq(ReportReason.abuse), eq("detail")))
			.thenReturn(new CreateReportResponse(900L));

		mockMvc.perform(post("/api/v1/answers/300/report")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "reason": "abuse",
					  "detail": "detail"
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(header().doesNotExist(HttpHeaders.LOCATION))
			.andExpect(jsonPath("$.reportId", is(900)));

		verify(reportService).createAnswer(any(AuthenticatedUser.class), eq(300L), eq(ReportReason.abuse), eq("detail"));
	}

	@Test
	void missingReasonMapsToValidationFailed() throws Exception {
		mockMvc.perform(post("/api/v1/answers/300/report")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("reason")));

		verifyNoInteractions(reportService);
	}

	@Test
	void detailLongerThanOneThousandCharactersMapsToValidationFailed() throws Exception {
		mockMvc.perform(post("/api/v1/answers/300/report")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"spam\",\"detail\":\"" + "a".repeat(1001) + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("detail")));

		verifyNoInteractions(reportService);
	}

	@Test
	void invalidReasonMapsToValidationFailed() throws Exception {
		mockMvc.perform(post("/api/v1/answers/300/report")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"bogus\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("reason")));

		verifyNoInteractions(reportService);
	}

	@Test
	void malformedAnswerIdMapsToValidationFailed() throws Exception {
		mockMvc.perform(post("/api/v1/answers/not-a-number/report")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"spam\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("answerId")));

		verifyNoInteractions(reportService);
	}

	@Test
	void missingAnswerMapsToNotFound() throws Exception {
		when(reportService.createAnswer(any(AuthenticatedUser.class), eq(999L), eq(ReportReason.spam), eq(null)))
			.thenThrow(new AnswerNotFoundException());

		mockMvc.perform(post("/api/v1/answers/999/report")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"spam\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("ANSWER_NOT_FOUND")));
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

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		ReportService reportService() {
			return mock(ReportService.class);
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
