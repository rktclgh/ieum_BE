package shinhan.fibri.ieum.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;

@WebMvcTest(StaticPageController.class)
@AutoConfigureMockMvc(addFilters = false)
class StaticPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void rootForwardsToRootIndexHtml() throws Exception {
		mockMvc.perform(get("/"))
			.andExpect(status().isOk())
			.andExpect(forwardedUrl("/index.html"));
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"/chats",
		"/chats/notices",
		"/chats/report",
		"/chats/room",
		"/chats/schedule",
		"/friends",
		"/join",
		"/join/social",
		"/login",
		"/meetups/detail",
		"/my",
		"/my/edit",
		"/my/inquiry",
		"/my/notifications",
		"/my/permissions",
		"/oauth/kakao/callback",
		"/questions",
		"/questions/detail",
		"/admin",
		"/admin/login",
		"/admin/users",
		"/admin/users/detail",
		"/admin/reports",
		"/admin/reports/detail",
		"/admin/inquiries"
	})
	void fixedPageWithAndWithoutTrailingSlashForwardsToNestedIndexHtml(String path) throws Exception {
		String expectedIndex = path + "/index.html";

		mockMvc.perform(get(path))
			.andExpect(status().isOk())
			.andExpect(forwardedUrl(expectedIndex));

		mockMvc.perform(get(path + "/"))
			.andExpect(status().isOk())
			.andExpect(forwardedUrl(expectedIndex));

		mockMvc.perform(head(path))
			.andExpect(status().isOk())
			.andExpect(forwardedUrl(expectedIndex));
	}

	@Test
	void queryParametersDoNotChangeTheForwardTarget() throws Exception {
		mockMvc.perform(get("/admin/users/detail").queryParam("userId", "10"))
			.andExpect(status().isOk())
			.andExpect(forwardedUrl("/admin/users/detail/index.html"));
	}

	@Test
	void removedSettingsPageIsNotForwarded() throws Exception {
		mockMvc.perform(get("/my/settings"))
			.andExpect(status().isNotFound());
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		SessionTokenValidator sessionTokenValidator() {
			return mock(SessionTokenValidator.class);
		}
	}
}
