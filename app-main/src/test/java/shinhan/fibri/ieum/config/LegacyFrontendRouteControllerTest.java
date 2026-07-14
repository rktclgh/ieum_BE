package shinhan.fibri.ieum.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MockMvc;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;

@WebMvcTest(LegacyFrontendRouteController.class)
@AutoConfigureMockMvc(addFilters = false)
class LegacyFrontendRouteControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@ParameterizedTest
	@CsvSource({
		"/chats/17, /chats/room/?chatId=17",
		"/chats/17/notices, /chats/notices/?chatId=17",
		"/chats/17/schedule, /chats/schedule/?chatId=17",
		"/meetups/81, /meetups/detail/?meetingId=81",
		"/questions/205, /questions/detail/?questionId=205",
		"/questions/9223372036854775807, /questions/detail/?questionId=9223372036854775807"
	})
	void positiveNumericLegacyRouteRedirectsTemporarily(String legacyPath, String canonicalLocation)
		throws Exception {
		mockMvc.perform(get(legacyPath))
			.andExpect(status().isFound())
			.andExpect(header().string("Location", canonicalLocation));
	}

	@Test
	void reportRouteRedirectsWithRequiredIds() throws Exception {
		mockMvc.perform(get("/chats/17/report").queryParam("messageId", "992"))
			.andExpect(status().isFound())
			.andExpect(header().string(
				"Location",
				"/chats/report/?chatId=17&messageId=992"
			));
	}

	@Test
	void reportTargetIsEncodedExactlyOnce() throws Exception {
		mockMvc.perform(get("/chats/17/report")
				.queryParam("messageId", "992")
				.queryParam("target", "민지 김"))
			.andExpect(status().isFound())
			.andExpect(header().string(
				"Location",
				"/chats/report/?chatId=17&messageId=992&target=%EB%AF%BC%EC%A7%80%20%EA%B9%80"
			))
			.andExpect(header().string("Location", not(containsString("%25EB"))));
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"/chats/0",
		"/chats/01",
		"/chats/-1",
		"/chats/1.5",
		"/chats/not-a-number",
		"/meetups/9223372036854775808",
		"/questions/99999999999999999999999999999999999999"
	})
	void invalidOrOutOfRangePathIdDoesNotRedirect(String legacyPath) throws Exception {
		mockMvc.perform(get(legacyPath))
			.andExpect(status().isNotFound())
			.andExpect(header().doesNotExist("Location"));
	}

	@Test
	void reportWithoutMessageIdDoesNotRedirect() throws Exception {
		mockMvc.perform(get("/chats/17/report"))
			.andExpect(status().isNotFound())
			.andExpect(header().doesNotExist("Location"));
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"0",
		"01",
		"-1",
		"1.5",
		"not-a-number",
		"9223372036854775808"
	})
	void reportWithInvalidOrOutOfRangeMessageIdDoesNotRedirect(String messageId) throws Exception {
		mockMvc.perform(get("/chats/17/report").queryParam("messageId", messageId))
			.andExpect(status().isNotFound())
			.andExpect(header().doesNotExist("Location"));
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
