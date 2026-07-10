package shinhan.fibri.ieum.main.notification.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.dto.NotificationItem;
import shinhan.fibri.ieum.main.notification.dto.NotificationListResponse;
import shinhan.fibri.ieum.main.notification.dto.NotificationReadAllResponse;
import shinhan.fibri.ieum.main.notification.exception.InvalidNotificationCursorException;
import shinhan.fibri.ieum.main.notification.exception.NotificationNotFoundException;
import shinhan.fibri.ieum.main.notification.service.NotificationService;

@WebMvcTest(NotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
class NotificationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private NotificationService notificationService;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
		reset(notificationService);
	}

	@Test
	void listNotificationsReturnsItemsCursorAndUnreadCount() throws Exception {
		when(notificationService.list(42L, "cursor", 2)).thenReturn(new NotificationListResponse(
			List.of(notificationItem()),
			"next-cursor",
			3L
		));

		mockMvc.perform(get("/api/v1/notifications")
				.with(authenticated())
				.param("cursor", "cursor")
				.param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].notificationId", is(10)))
			.andExpect(jsonPath("$.items[0].type", is("question")))
			.andExpect(jsonPath("$.items[0].isRead", is(false)))
			.andExpect(jsonPath("$.nextCursor", is("next-cursor")))
			.andExpect(jsonPath("$.unreadCount", is(3)));

		verify(notificationService).list(42L, "cursor", 2);
	}

	@Test
	void marksNotificationReadWithNoContent() throws Exception {
		mockMvc.perform(post("/api/v1/notifications/{notificationId}/read", 10L).with(authenticated()))
			.andExpect(status().isNoContent());

		verify(notificationService).markRead(42L, 10L);
	}

	@Test
	void marksAllNotificationsRead() throws Exception {
		when(notificationService.markAllRead(42L)).thenReturn(new NotificationReadAllResponse(3));

		mockMvc.perform(post("/api/v1/notifications/read-all").with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.updated", is(3)));

		verify(notificationService).markAllRead(42L);
	}

	@Test
	void deletesNotificationWithNoContent() throws Exception {
		mockMvc.perform(delete("/api/v1/notifications/{notificationId}", 10L).with(authenticated()))
			.andExpect(status().isNoContent());

		verify(notificationService).delete(42L, 10L);
	}

	@Test
	void mapsInvalidCursorToBadRequest() throws Exception {
		when(notificationService.list(eq(42L), eq("bad"), any())).thenThrow(new InvalidNotificationCursorException());

		mockMvc.perform(get("/api/v1/notifications")
				.with(authenticated())
				.param("cursor", "bad"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("INVALID_CURSOR")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("cursor")));
	}

	@Test
	void mapsMissingOrOtherUsersNotificationToNotFound() throws Exception {
		doThrow(new NotificationNotFoundException())
			.when(notificationService).markRead(42L, 10L);

		mockMvc.perform(post("/api/v1/notifications/{notificationId}/read", 10L).with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("NOTIFICATION_NOT_FOUND")));
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

	private static NotificationItem notificationItem() {
		return new NotificationItem(
			10L,
			NotificationType.question,
			"새 답변",
			"질문에 답변이 달렸어요",
			5L,
			false,
			OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
		);
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		NotificationService notificationService() {
			return mock(NotificationService.class);
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
