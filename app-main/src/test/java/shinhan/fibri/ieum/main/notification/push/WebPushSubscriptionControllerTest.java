package shinhan.fibri.ieum.main.notification.push;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import shinhan.fibri.ieum.main.auth.session.AuthenticatedSessionDetails;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;

@WebMvcTest(WebPushSubscriptionController.class)
@AutoConfigureMockMvc(addFilters = false)
class WebPushSubscriptionControllerTest {

	private static final String VALID_REQUEST = """
		{
		  "endpoint": "https://fcm.googleapis.com/push/123",
		  "expirationTime": null,
		  "keys": {
		    "p256dh": "p256dh-value",
		    "auth": "auth-value"
		  }
		}
		""";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private WebPushSubscriptionService service;

	@AfterEach
	void cleanUp() {
		SecurityContextHolder.clearContext();
		reset(service);
	}

	@Test
	void configReturnsOnlyPublicFeatureStateForCurrentSession() throws Exception {
		when(service.config(42L, "session-42"))
			.thenReturn(new WebPushConfigResponse(true, "public-vapid-key", true));

		mockMvc.perform(get("/api/v1/notifications/push/config").with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.enabled", is(true)))
			.andExpect(jsonPath("$.vapidPublicKey", is("public-vapid-key")))
			.andExpect(jsonPath("$.subscribed", is(true)))
			.andExpect(jsonPath("$.endpoint").doesNotExist())
			.andExpect(jsonPath("$.p256dh").doesNotExist())
			.andExpect(jsonPath("$.auth").doesNotExist())
			.andExpect(jsonPath("$.sessionId").doesNotExist());

		verify(service).config(42L, "session-42");
	}

	@Test
	void subscribeBindsBodyToAuthenticatedUserAndSession() throws Exception {
		mockMvc.perform(put("/api/v1/notifications/push/subscription")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content(VALID_REQUEST))
			.andExpect(status().isNoContent());

		verify(service).subscribe(
			42L,
			"session-42",
			new WebPushSubscriptionRequest(
				"https://fcm.googleapis.com/push/123",
				null,
				new WebPushSubscriptionRequest.Keys("p256dh-value", "auth-value")
			)
		);
	}

	@Test
	void unsubscribeDeletesCurrentSessionIdempotently() throws Exception {
		mockMvc.perform(delete("/api/v1/notifications/push/subscription").with(authenticated()))
			.andExpect(status().isNoContent());

		verify(service).unsubscribe("session-42");
	}

	@Test
	void missingTypedSessionDetailsFailsClosed() throws Exception {
		mockMvc.perform(get("/api/v1/notifications/push/config").with(authenticationWithDetails("session-42")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("AUTHENTICATION_REQUIRED")));

		verifyNoInteractions(service);
	}

	@Test
	void validationFailureReturnsStableFieldError() throws Exception {
		doThrow(new InvalidWebPushSubscriptionException("endpoint", "Invalid endpoint"))
			.when(service).subscribe(any(Long.class), any(String.class), any(WebPushSubscriptionRequest.class));

		mockMvc.perform(put("/api/v1/notifications/push/subscription")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content(VALID_REQUEST))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("endpoint")))
			.andExpect(jsonPath("$.fieldErrors[0].message", is("Invalid endpoint")));
	}

	@Test
	void disabledSubscriptionReturnsServiceUnavailable() throws Exception {
		doThrow(new WebPushDisabledException()).when(service)
			.subscribe(any(Long.class), any(String.class), any(WebPushSubscriptionRequest.class));

		mockMvc.perform(put("/api/v1/notifications/push/subscription")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content(VALID_REQUEST))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code", is("WEB_PUSH_DISABLED")));
	}

	@Test
	void malformedJsonRemainsGlobalBadRequest() throws Exception {
		mockMvc.perform(put("/api/v1/notifications/push/subscription")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("BAD_REQUEST")));
	}

	private static RequestPostProcessor authenticated() {
		return authenticationWithDetails(new AuthenticatedSessionDetails("session-42"));
	}

	private static RequestPostProcessor authenticationWithDetails(Object details) {
		return request -> {
			AuthenticatedUser principal = new AuthenticatedUser(
				42L,
				"user@example.com",
				UserRole.user,
				UserStatus.active
			);
			TestingAuthenticationToken authentication = new TestingAuthenticationToken(principal, null);
			authentication.setDetails(details);
			SecurityContextHolder.getContext().setAuthentication(authentication);
			request.setUserPrincipal(authentication);
			return request;
		};
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		WebPushSubscriptionService webPushSubscriptionService() {
			return mock(WebPushSubscriptionService.class);
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
