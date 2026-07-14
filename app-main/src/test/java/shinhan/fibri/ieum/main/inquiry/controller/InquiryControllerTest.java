package shinhan.fibri.ieum.main.inquiry.controller;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
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
import shinhan.fibri.ieum.main.inquiry.domain.InquiryStatus;
import shinhan.fibri.ieum.main.inquiry.dto.CreateInquiryRequest;
import shinhan.fibri.ieum.main.inquiry.dto.CreateInquiryResponse;
import shinhan.fibri.ieum.main.inquiry.dto.InquiryItem;
import shinhan.fibri.ieum.main.inquiry.dto.InquiryListResponse;
import shinhan.fibri.ieum.main.inquiry.dto.SuspendedUserInquiryRequest;
import shinhan.fibri.ieum.main.inquiry.service.SuspendedUserInquiryRateLimiter;
import shinhan.fibri.ieum.main.inquiry.service.InquiryService;
import shinhan.fibri.ieum.main.inquiry.service.SuspendedUserInquiryService;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

@WebMvcTest(InquiryController.class)
@AutoConfigureMockMvc(addFilters = false)
class InquiryControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private InquiryService inquiryService;

	@Autowired
	private SuspendedUserInquiryService suspendedUserInquiryService;

	@Autowired
	private SuspendedUserInquiryRateLimiter suspendedUserInquiryRateLimiter;

	@BeforeEach
	void allowSuspendedUserInquiryRateLimit() {
		when(suspendedUserInquiryRateLimiter.tryAcquire(any())).thenReturn(true);
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
		reset(inquiryService, suspendedUserInquiryService, suspendedUserInquiryRateLimiter);
	}

	@Test
	void createsInquiryWithLocationAndInquiryId() throws Exception {
		when(inquiryService.create(any(AuthenticatedUser.class), any(CreateInquiryRequest.class)))
			.thenReturn(new CreateInquiryResponse(90L));

		mockMvc.perform(post("/api/v1/inquiries")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"문의 내용\"}"))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/inquiries/90"))
			.andExpect(jsonPath("$.inquiryId", is(90)));

		verify(inquiryService).create(any(AuthenticatedUser.class), any(CreateInquiryRequest.class));
	}

	@Test
	void listsCurrentUsersInquiries() throws Exception {
		when(inquiryService.listMine(42L)).thenReturn(new InquiryListResponse(List.of(
			new InquiryItem(
				90L,
				"문의 제목",
				"문의 내용",
				InquiryStatus.answered,
				"관리자 답변",
				OffsetDateTime.parse("2026-07-13T11:00:00+09:00"),
				OffsetDateTime.parse("2026-07-13T10:00:00+09:00")
			)
		)));

		mockMvc.perform(get("/api/v1/inquiries/me").with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].inquiryId", is(90)))
			.andExpect(jsonPath("$.items[0].status", is("answered")))
			.andExpect(jsonPath("$.items[0].answer", is("관리자 답변")))
			.andExpect(jsonPath("$.items[0].answeredAt", is("2026-07-13T11:00:00+09:00")));
	}

	@Test
	void validatesBlankContentAndFieldLengths() throws Exception {
		mockMvc.perform(post("/api/v1/inquiries")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"가" + "나".repeat(50) + "\",\"content\":\" \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[*].field", hasItems("content", "title")));

		verifyNoInteractions(inquiryService);
	}

	@Test
	void rejectsContentLongerThanTwoThousandCharacters() throws Exception {
		mockMvc.perform(post("/api/v1/inquiries")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"" + "가".repeat(2001) + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[*].field", hasItems("content")));

		verifyNoInteractions(inquiryService);
	}

	@Test
	void mapsMissingUserToNotFound() throws Exception {
		when(inquiryService.create(any(AuthenticatedUser.class), any(CreateInquiryRequest.class)))
			.thenThrow(new UserNotFoundException());

		mockMvc.perform(post("/api/v1/inquiries")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"문의 내용\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("USER_NOT_FOUND")));
	}

	@Test
	void sendsSuspendedUserInquiryWithoutAuthentication() throws Exception {
		mockMvc.perform(post("/api/v1/inquiries/suspended-users")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":" user@example.com ","title":" 제재 문의 ","content":"로그인이 안 됩니다."}
					"""))
			.andExpect(status().isOk());

		verify(suspendedUserInquiryService).send(any(SuspendedUserInquiryRequest.class));
	}

	@Test
	void validatesSuspendedUserInquiryRequest() throws Exception {
		mockMvc.perform(post("/api/v1/inquiries/suspended-users")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"not-email","title":"","content":""}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[*].field", hasItems("email", "title", "content")));

		verifyNoInteractions(suspendedUserInquiryService);
	}

	@Test
	void rateLimitsSuspendedUserInquiryByClientIp() throws Exception {
		when(suspendedUserInquiryRateLimiter.tryAcquire("203.0.113.10")).thenReturn(false);

		mockMvc.perform(post("/api/v1/inquiries/suspended-users")
				.with(request -> {
					request.setRemoteAddr("203.0.113.10");
					return request;
				})
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"user@example.com","title":"제재 문의","content":"로그인이 안 됩니다."}
					"""))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().string("Retry-After", "60"))
			.andExpect(jsonPath("$.code", is("INQUIRY_RATE_LIMITED")));

		verifyNoInteractions(suspendedUserInquiryService);
	}

	@Test
	void mapsSuspendedUserInquiryMailFailureToBadGateway() throws Exception {
		org.mockito.Mockito.doThrow(new CompletionException(new MailSendException("smtp down")))
			.when(suspendedUserInquiryService)
			.send(any(SuspendedUserInquiryRequest.class));

		mockMvc.perform(post("/api/v1/inquiries/suspended-users")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"user@example.com","title":"제재 문의","content":"로그인이 안 됩니다."}
					"""))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code", is("INQUIRY_MAIL_DELIVERY_FAILED")));
	}

	@Test
	void mapsSynchronousSuspendedUserInquiryMailFailureToBadGateway() throws Exception {
		org.mockito.Mockito.doThrow(new MailSendException("smtp down"))
			.when(suspendedUserInquiryService)
			.send(any(SuspendedUserInquiryRequest.class));

		mockMvc.perform(post("/api/v1/inquiries/suspended-users")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"user@example.com","title":"제재 문의","content":"로그인이 안 됩니다."}
					"""))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code", is("INQUIRY_MAIL_DELIVERY_FAILED")));
	}

	private static RequestPostProcessor authenticated() {
		return request -> {
			AuthenticatedUser principal = new AuthenticatedUser(
				42L,
				"user@example.com",
				UserRole.user,
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
		InquiryService inquiryService() {
			return mock(InquiryService.class);
		}

		@Bean
		@Primary
		SuspendedUserInquiryService suspendedUserInquiryService() {
			return mock(SuspendedUserInquiryService.class);
		}

		@Bean
		@Primary
		SuspendedUserInquiryRateLimiter suspendedUserInquiryRateLimiter() {
			return mock(SuspendedUserInquiryRateLimiter.class);
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
