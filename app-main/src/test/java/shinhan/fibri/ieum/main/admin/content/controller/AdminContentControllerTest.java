package shinhan.fibri.ieum.main.admin.content.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
import shinhan.fibri.ieum.main.admin.content.exception.ContentNotFoundException;
import shinhan.fibri.ieum.main.admin.content.exception.HardDeleteConfirmationMismatchException;
import shinhan.fibri.ieum.main.admin.content.dto.AdminContentPreviewResponse;
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
	void previewQuestionReturnsPreviewBody() throws Exception {
		when(adminContentService.preview("question", 42L)).thenReturn(new AdminContentPreviewResponse(
			"question",
			42L,
			"question title",
			"author",
			2L,
			OffsetDateTime.parse("2026-07-01T00:00:00Z"),
			OffsetDateTime.parse("2026-07-02T00:00:00Z")
		));

		mockMvc.perform(get("/api/v1/admin/content/question/42").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.contentType", is("question")))
			.andExpect(jsonPath("$.contentId", is(42)))
			.andExpect(jsonPath("$.title", is("question title")))
			.andExpect(jsonPath("$.authorNickname", is("author")))
			.andExpect(jsonPath("$.authorId", is(2)))
			.andExpect(jsonPath("$.deletedAt", is("2026-07-02T00:00:00Z")));
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
