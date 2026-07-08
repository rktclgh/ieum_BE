package shinhan.fibri.ieum.main.chat.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import shinhan.fibri.ieum.common.chat.domain.RoomType;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomResponse;
import shinhan.fibri.ieum.main.chat.exception.BlockedChatException;
import shinhan.fibri.ieum.main.chat.exception.NotFriendsException;
import shinhan.fibri.ieum.main.chat.exception.SelfChatRoomException;
import shinhan.fibri.ieum.main.chat.service.ChatService;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

@WebMvcTest(ChatController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChatControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ChatService chatService;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
		reset(chatService);
	}

	@Test
	void createDirectRoomReturnsRoom() throws Exception {
		when(chatService.createDirectRoom(any(AuthenticatedUser.class), eq(77L)))
			.thenReturn(new ChatRoomResponse(100L, RoomType.direct, null, null));

		mockMvc.perform(post("/api/v1/chat/rooms/direct")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"friendId\":77}")
				.with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.roomId", is(100)))
			.andExpect(jsonPath("$.roomType", is("direct")))
			.andExpect(jsonPath("$.meetingId").doesNotExist())
			.andExpect(jsonPath("$.questionId").doesNotExist());

		verify(chatService).createDirectRoom(any(AuthenticatedUser.class), eq(77L));
	}

	@Test
	void createDirectRoomValidatesFriendId() throws Exception {
		mockMvc.perform(post("/api/v1/chat/rooms/direct")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}")
				.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("friendId")));
	}

	@Test
	void mapsSelfDirectRoomToBadRequest() throws Exception {
		doThrow(new SelfChatRoomException())
			.when(chatService).createDirectRoom(any(AuthenticatedUser.class), eq(42L));

		mockMvc.perform(post("/api/v1/chat/rooms/direct")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"friendId\":42}")
				.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("SELF_CHAT_ROOM")));
	}

	@Test
	void mapsNotFriendsToForbidden() throws Exception {
		doThrow(new NotFriendsException())
			.when(chatService).createDirectRoom(any(AuthenticatedUser.class), eq(77L));

		mockMvc.perform(post("/api/v1/chat/rooms/direct")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"friendId\":77}")
				.with(authenticated()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("NOT_FRIENDS")));
	}

	@Test
	void mapsBlockedChatToForbidden() throws Exception {
		doThrow(new BlockedChatException())
			.when(chatService).createDirectRoom(any(AuthenticatedUser.class), eq(77L));

		mockMvc.perform(post("/api/v1/chat/rooms/direct")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"friendId\":77}")
				.with(authenticated()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("BLOCKED")));
	}

	@Test
	void mapsUserNotFoundToNotFound() throws Exception {
		doThrow(new UserNotFoundException())
			.when(chatService).createDirectRoom(any(AuthenticatedUser.class), eq(77L));

		mockMvc.perform(post("/api/v1/chat/rooms/direct")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"friendId\":77}")
				.with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("USER_NOT_FOUND")));
	}

	private static RequestPostProcessor authenticated() {
		return request -> {
			AuthenticatedUser principal = new AuthenticatedUser(
				42L,
				"user@example.com",
				UserRole.user,
				UserStatus.active
			);
			SecurityContextHolder.getContext()
				.setAuthentication(new TestingAuthenticationToken(principal, null));
			return request;
		};
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		ChatService chatService() {
			return mock(ChatService.class);
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
