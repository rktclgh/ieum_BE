package shinhan.fibri.ieum.main.notification.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.notification.sse.SseAuthenticationRequiredException;
import shinhan.fibri.ieum.main.notification.sse.SseSubscriptionService;

@WebMvcTest(SseController.class)
@AutoConfigureMockMvc(addFilters = false)
class SseControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SseSubscriptionService subscriptionService;

	@AfterEach
	void resetMocks() {
		reset(subscriptionService);
	}

	@Test
	void opensEventStreamWithNoCacheAndNginxBufferingDisabled() throws Exception {
		when(subscriptionService.subscribe("access-token")).thenReturn(new SseEmitter(1_000L));

		mockMvc.perform(get("/api/v1/sse/subscribe").cookie(new Cookie("access_token", "access-token")))
			.andExpect(request().asyncStarted())
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-cache"))
			.andExpect(header().string("X-Accel-Buffering", "no"))
			.andExpect(header().string("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE));
	}

	@Test
	void returnsJsonUnauthorizedWhenSessionCannotBeValidated() throws Exception {
		when(subscriptionService.subscribe(null)).thenThrow(new SseAuthenticationRequiredException());

		mockMvc.perform(get("/api/v1/sse/subscribe"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("AUTHENTICATION_REQUIRED")));
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		SseSubscriptionService subscriptionService() {
			return mock(SseSubscriptionService.class);
		}

		@Bean
		@Primary
		SessionTokenValidator sessionTokenValidator() {
			return mock(SessionTokenValidator.class);
		}
	}
}
