package shinhan.fibri.ieum.main.report.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.report.dto.CreateReportRequest;
import shinhan.fibri.ieum.main.report.dto.CreateReportResponse;
import shinhan.fibri.ieum.main.report.exception.ReportMessageNotFoundException;
import shinhan.fibri.ieum.main.report.service.ReportService;

@WebMvcTest(ReportController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReportControllerTest {

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
	void createReportReturnsCreated() throws Exception {
		when(reportService.create(any(AuthenticatedUser.class), any(CreateReportRequest.class)))
			.thenReturn(new CreateReportResponse(900L));

		mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "messageId": 500,
					  "reason": "abuse",
					  "detail": "욕설과 공격적인 표현"
					}
					""")
				.with(authenticated()))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "/api/v1/reports/900"))
			.andExpect(jsonPath("$.reportId", is(900)));

		verify(reportService).create(any(AuthenticatedUser.class), any(CreateReportRequest.class));
	}

	@Test
	void createReportValidatesRequiredFields() throws Exception {
		mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}")
				.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));

		verifyNoInteractions(reportService);
	}

	@Test
	void mapsMissingMessageToNotFound() throws Exception {
		when(reportService.create(any(AuthenticatedUser.class), any(CreateReportRequest.class)))
			.thenThrow(new ReportMessageNotFoundException());

		mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "messageId": 500,
					  "reason": "spam"
					}
					""")
				.with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("REPORT_MESSAGE_NOT_FOUND")));
	}

	@Test
	void mapsNotRoomMemberToForbidden() throws Exception {
		doThrow(new NotRoomMemberException())
			.when(reportService).create(any(AuthenticatedUser.class), any(CreateReportRequest.class));

		mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "messageId": 500,
					  "reason": "spam"
					}
					""")
				.with(authenticated()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("NOT_ROOM_MEMBER")));
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
