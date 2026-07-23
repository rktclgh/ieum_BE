package shinhan.fibri.ieum.main.admin.content.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentDetailResponse;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentListItem;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentListResponse;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentUpdateRequest;
import shinhan.fibri.ieum.main.admin.content.exception.ContentNotFoundException;
import shinhan.fibri.ieum.main.admin.content.exception.HardDeleteConfirmationMismatchException;
import shinhan.fibri.ieum.main.admin.content.exception.InvalidAdminContentCursorException;
import shinhan.fibri.ieum.main.admin.content.exception.UnsupportedContentTypeException;
import shinhan.fibri.ieum.main.admin.content.service.AdminContentService;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;

@WebMvcTest(AdminContentController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminContentControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AdminContentService adminContentService;

	@AfterEach
	void resetMocks() {
		SecurityContextHolder.clearContext();
		reset(adminContentService);
	}

	@Test
	void deleteQuestionReturnsNoContentWithoutBody() throws Exception {
		mockMvc.perform(delete("/api/v1/admin/content/question/200").with(admin()))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		verify(adminContentService).hide("question", 200L);
	}

	@Test
	void missingQuestionMapsToContentNotFound() throws Exception {
		doThrow(new ContentNotFoundException()).when(adminContentService).hide("question", 999L);

		mockMvc.perform(delete("/api/v1/admin/content/question/999").with(admin()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("CONTENT_NOT_FOUND")));
	}

	@Test
	void unsupportedTypeMapsToNotImplemented() throws Exception {
		doThrow(new UnsupportedContentTypeException("meeting")).when(adminContentService).hide("meeting", 100L);

		mockMvc.perform(delete("/api/v1/admin/content/meeting/100").with(admin()))
			.andExpect(status().isNotImplemented())
			.andExpect(jsonPath("$.code", is("CONTENT_TYPE_NOT_IMPLEMENTED")));
	}

	@Test
	void getQuestionsReturnsCursorPage() throws Exception {
		when(adminContentService.getQuestions(org.mockito.ArgumentMatchers.any())).thenReturn(new AdminContentListResponse(
			java.util.List.of(new AdminContentListItem(
				"question",
				42L,
				"question title",
				"author",
				2L,
				OffsetDateTime.parse("2026-07-01T00:00:00Z"),
				OffsetDateTime.parse("2026-07-02T00:00:00Z"),
				null,
				false,
				null,
				null
			)),
			"41"
		));

		mockMvc.perform(get("/api/v1/admin/content/questions")
				.with(admin())
				.param("cursor", "41")
				.param("size", "20"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nextCursor", is("41")))
			.andExpect(jsonPath("$.items[0].contentType", is("question")))
			.andExpect(jsonPath("$.items[0].contentId", is(42)))
			.andExpect(jsonPath("$.items[0].title", is("question title")));

		verify(adminContentService).getQuestions(org.mockito.ArgumentMatchers.argThat(request ->
			"41".equals(request.cursor()) && request.size().equals(20)));
	}

	@Test
	void getMeetingsReturnsCursorPage() throws Exception {
		when(adminContentService.getMeetings(org.mockito.ArgumentMatchers.any())).thenReturn(new AdminContentListResponse(
			java.util.List.of(new AdminContentListItem(
				"meeting",
				7L,
				"meeting title",
				"host",
				3L,
				OffsetDateTime.parse("2026-07-01T00:00:00Z"),
				OffsetDateTime.parse("2026-07-02T00:00:00Z"),
				null,
				null,
				"open",
				4
			)),
			null
		));

		mockMvc.perform(get("/api/v1/admin/content/meetings").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].contentType", is("meeting")))
			.andExpect(jsonPath("$.items[0].status", is("open")))
			.andExpect(jsonPath("$.items[0].participantCount", is(4)));
	}

	@Test
	void detailQuestionReturnsEditableContentBody() throws Exception {
		when(adminContentService.detail("question", 42L)).thenReturn(new AdminContentDetailResponse(
			"question",
			42L,
			"question title",
			"question content",
			"author",
			2L,
			OffsetDateTime.parse("2026-07-01T00:00:00Z"),
			OffsetDateTime.parse("2026-07-02T00:00:00Z"),
			null,
			false,
			null,
			null
		));

		mockMvc.perform(get("/api/v1/admin/content/question/42").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.contentType", is("question")))
			.andExpect(jsonPath("$.contentId", is(42)))
			.andExpect(jsonPath("$.title", is("question title")))
			.andExpect(jsonPath("$.content", is("question content")))
			.andExpect(jsonPath("$.authorNickname", is("author")))
			.andExpect(jsonPath("$.authorId", is(2)))
			.andExpect(jsonPath("$.updatedAt", is("2026-07-02T00:00:00Z")));
	}

	@Test
	void updateQuestionReturnsDetailAndPassesPrincipal() throws Exception {
		when(adminContentService.update(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.eq("question"),
			org.mockito.ArgumentMatchers.eq(42L),
			org.mockito.ArgumentMatchers.any(AdminContentUpdateRequest.class)
		)).thenReturn(new AdminContentDetailResponse(
			"question",
			42L,
			"updated title",
			"updated content",
			"author",
			2L,
			OffsetDateTime.parse("2026-07-01T00:00:00Z"),
			OffsetDateTime.parse("2026-07-02T00:00:00Z"),
			null,
			false,
			null,
			null
		));

		mockMvc.perform(patch("/api/v1/admin/content/question/42")
				.with(admin())
				.contentType("application/json")
				.content("""
					{"title":"updated title","content":"updated content"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title", is("updated title")))
			.andExpect(jsonPath("$.content", is("updated content")));

		verify(adminContentService).update(
			org.mockito.ArgumentMatchers.argThat(principal -> principal.userId().equals(1L)),
			org.mockito.ArgumentMatchers.eq("question"),
			org.mockito.ArgumentMatchers.eq(42L),
			org.mockito.ArgumentMatchers.argThat(request ->
				request.title().equals("updated title") && request.content().equals("updated content"))
		);
	}

	@Test
	void hardDeleteQuestionReturnsNoContentAndPassesPrincipalAndToken() throws Exception {
		mockMvc.perform(delete("/api/v1/admin/content/question/42/hard")
				.with(admin())
				.contentType("application/json")
				.content("""
					{"confirmationToken":"DELETE QUESTION 42"}
					"""))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		verify(adminContentService).hardDelete(
			org.mockito.ArgumentMatchers.argThat(principal -> principal.userId().equals(1L)),
			org.mockito.ArgumentMatchers.eq("question"),
			org.mockito.ArgumentMatchers.eq(42L),
			org.mockito.ArgumentMatchers.eq("DELETE QUESTION 42")
		);
	}

	@Test
	void hardDeleteBadTokenMapsToValidationFailed() throws Exception {
		doThrow(new HardDeleteConfirmationMismatchException())
			.when(adminContentService)
			.hardDelete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("question"), org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq("DELETE QUESTION 41"));

		mockMvc.perform(delete("/api/v1/admin/content/question/42/hard")
				.with(admin())
				.contentType("application/json")
				.content("""
					{"confirmationToken":"DELETE QUESTION 41"}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
	}

	@Test
	void listSizeValidationMapsToValidationFailed() throws Exception {
		mockMvc.perform(get("/api/v1/admin/content/questions")
				.with(admin())
				.param("size", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.message", is("Request validation failed")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("size")));
	}

	@Test
	void invalidCursorMapsToValidationFailed() throws Exception {
		when(adminContentService.getQuestions(org.mockito.ArgumentMatchers.any()))
			.thenThrow(new InvalidAdminContentCursorException());

		mockMvc.perform(get("/api/v1/admin/content/questions")
				.with(admin())
				.param("cursor", "invalid"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.message", is("Request validation failed")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("cursor")));
	}

	@Test
	void pathIdTypeMismatchMapsToValidationFailed() throws Exception {
		mockMvc.perform(get("/api/v1/admin/content/question/not-a-number").with(admin()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.message", is("Request validation failed")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("id")))
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
		AdminContentService adminContentService() {
			return mock(AdminContentService.class);
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
