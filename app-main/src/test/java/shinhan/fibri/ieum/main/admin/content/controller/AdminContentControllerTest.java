package shinhan.fibri.ieum.main.admin.content.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.content.exception.ContentNotFoundException;
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
	}
}
