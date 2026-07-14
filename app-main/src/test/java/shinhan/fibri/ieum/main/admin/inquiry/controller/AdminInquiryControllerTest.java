package shinhan.fibri.ieum.main.admin.inquiry.controller;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import shinhan.fibri.ieum.main.admin.inquiry.dto.AdminInquiryItem;
import shinhan.fibri.ieum.main.admin.inquiry.dto.AdminInquiryListRequest;
import shinhan.fibri.ieum.main.admin.inquiry.dto.AnswerInquiryRequest;
import shinhan.fibri.ieum.main.admin.user.dto.CursorPage;
import shinhan.fibri.ieum.main.admin.inquiry.exception.InquiryAlreadyAnsweredException;
import shinhan.fibri.ieum.main.admin.inquiry.exception.InquiryNotFoundException;
import shinhan.fibri.ieum.main.admin.user.exception.InvalidAdminCursorException;
import shinhan.fibri.ieum.main.admin.inquiry.service.AdminInquiryAnswerService;
import shinhan.fibri.ieum.main.admin.inquiry.service.AdminInquiryQueryService;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.inquiry.domain.InquiryStatus;

@WebMvcTest(AdminInquiryController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminInquiryControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AdminInquiryQueryService queryService;

	@Autowired
	private AdminInquiryAnswerService answerService;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
		reset(queryService, answerService);
	}

	@Test
	void listsAdminInquiriesFilteredByStatus() throws Exception {
		when(queryService.list(new AdminInquiryListRequest(InquiryStatus.pending, null, null)))
			.thenReturn(new CursorPage<>(List.of(item()), "next"));

		mockMvc.perform(get("/api/v1/admin/inquiries")
				.with(admin())
				.param("status", "pending"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].inquiryId", is(90)))
			.andExpect(jsonPath("$.items[0].userEmail", is("user@example.com")))
			.andExpect(jsonPath("$.items[0].status", is("pending")))
			.andExpect(jsonPath("$.nextCursor", is("next")));
	}

	@Test
	void rejectsInquiryListSizeGreaterThanFifty() throws Exception {
		mockMvc.perform(get("/api/v1/admin/inquiries")
				.with(admin())
				.param("size", "51"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[*].field", hasItems("size")));

		verifyNoInteractions(queryService);
	}

	@Test
	void invalidCursorMapsToBadRequest() throws Exception {
		when(queryService.list(any())).thenThrow(new InvalidAdminCursorException());

		mockMvc.perform(get("/api/v1/admin/inquiries")
				.with(admin())
				.param("cursor", "bad"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("INVALID_CURSOR")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("cursor")));
	}

	@Test
	void answersInquiryAndReturnsEmptyOk() throws Exception {
		mockMvc.perform(post("/api/v1/admin/inquiries/90/answer")
				.with(admin())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"answer":"답변 내용"}
					"""))
			.andExpect(status().isOk())
			.andExpect(content().string(""));

		verify(answerService).answer(any(AuthenticatedUser.class), org.mockito.ArgumentMatchers.eq(90L), any(AnswerInquiryRequest.class));
	}

	@Test
	void validatesBlankAnswer() throws Exception {
		mockMvc.perform(post("/api/v1/admin/inquiries/90/answer")
				.with(admin())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"answer":" "}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[*].field", hasItems("answer")));

		verifyNoInteractions(answerService);
	}

	@Test
	void mapsMissingInquiryToNotFound() throws Exception {
		org.mockito.Mockito.doThrow(new InquiryNotFoundException())
			.when(answerService)
			.answer(any(), any(), any());

		mockMvc.perform(post("/api/v1/admin/inquiries/404/answer")
				.with(admin())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"answer":"답변 내용"}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("INQUIRY_NOT_FOUND")));
	}

	@Test
	void mapsAlreadyAnsweredInquiryToConflict() throws Exception {
		org.mockito.Mockito.doThrow(new InquiryAlreadyAnsweredException())
			.when(answerService)
			.answer(any(), any(), any());

		mockMvc.perform(post("/api/v1/admin/inquiries/90/answer")
				.with(admin())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"answer":"답변 내용"}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("INQUIRY_ALREADY_ANSWERED")));
	}

	private static RequestPostProcessor admin() {
		return request -> {
			AuthenticatedUser principal = new AuthenticatedUser(
				1L,
				"admin@example.com",
				UserRole.admin,
				UserStatus.active
			);
			SecurityContextHolder.getContext()
				.setAuthentication(new TestingAuthenticationToken(principal, null));
			return request;
		};
	}

	private static AdminInquiryItem item() {
		return new AdminInquiryItem(
			90L,
			42L,
			"user@example.com",
			"문의 제목",
			"문의 내용",
			InquiryStatus.pending,
			null,
			null,
			null,
			OffsetDateTime.parse("2026-07-13T10:00:00+09:00")
		);
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		AdminInquiryQueryService adminInquiryQueryService() {
			return mock(AdminInquiryQueryService.class);
		}

		@Bean
		@Primary
		AdminInquiryAnswerService adminInquiryAnswerService() {
			return mock(AdminInquiryAnswerService.class);
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
