package shinhan.fibri.ieum.main.friend.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import shinhan.fibri.ieum.main.friend.exception.AlreadyFriendsException;
import shinhan.fibri.ieum.main.friend.exception.BlockedFriendshipException;
import shinhan.fibri.ieum.main.friend.exception.CannotAcceptOwnFriendRequestException;
import shinhan.fibri.ieum.main.friend.exception.FriendRequestExistsException;
import shinhan.fibri.ieum.main.friend.exception.FriendshipNotFoundException;
import shinhan.fibri.ieum.main.friend.exception.SelfFriendActionException;
import shinhan.fibri.ieum.main.friend.exception.SelfFriendRequestException;
import shinhan.fibri.ieum.main.friend.dto.BlockedUserIdsResponse;
import shinhan.fibri.ieum.main.friend.dto.BlockedUserResponse;
import shinhan.fibri.ieum.main.friend.dto.FriendRequestResponse;
import shinhan.fibri.ieum.main.friend.dto.FriendResponse;
import shinhan.fibri.ieum.main.friend.service.FriendService;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

@WebMvcTest(FriendController.class)
@AutoConfigureMockMvc(addFilters = false)
class FriendControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private FriendService friendService;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
		reset(friendService);
	}

	@Test
	void listFriendsReturnsFriends() throws Exception {
		OffsetDateTime lastActiveAt = OffsetDateTime.parse("2026-07-08T10:00:00+09:00");
		when(friendService.listFriends(any(AuthenticatedUser.class)))
			.thenReturn(List.of(new FriendResponse(7L, "friend", "/api/v1/files/3", "KR", lastActiveAt, true)));

		mockMvc.perform(get("/api/v1/friends").with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].userId", is(7)))
			.andExpect(jsonPath("$[0].nickname", is("friend")))
			.andExpect(jsonPath("$[0].profileImageUrl", is("/api/v1/files/3")))
			.andExpect(jsonPath("$[0].nationality", is("KR")))
			.andExpect(jsonPath("$[0].lastActiveAt", is("2026-07-08T10:00:00+09:00")))
			.andExpect(jsonPath("$[0].active", is(true)));

		verify(friendService).listFriends(any(AuthenticatedUser.class));
	}

	@Test
	void listFriendRequestsReturnsRequests() throws Exception {
		OffsetDateTime requestedAt = OffsetDateTime.parse("2026-07-08T09:30:00+09:00");
		when(friendService.listFriendRequests(any(AuthenticatedUser.class), eq("received")))
			.thenReturn(List.of(new FriendRequestResponse(7L, "requester", null, "JP", requestedAt)));

		mockMvc.perform(get("/api/v1/friends/requests")
				.param("direction", "received")
				.with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].userId", is(7)))
			.andExpect(jsonPath("$[0].nickname", is("requester")))
			.andExpect(jsonPath("$[0].profileImageUrl").doesNotExist())
			.andExpect(jsonPath("$[0].nationality", is("JP")))
			.andExpect(jsonPath("$[0].requestedAt", is("2026-07-08T09:30:00+09:00")));

		verify(friendService).listFriendRequests(any(AuthenticatedUser.class), eq("received"));
	}

	@Test
	void listBlocksReturnsBlockedUsers() throws Exception {
		OffsetDateTime blockedAt = OffsetDateTime.parse("2026-07-08T08:00:00+09:00");
		when(friendService.listBlocks(any(AuthenticatedUser.class)))
			.thenReturn(List.of(new BlockedUserResponse(8L, "blocked", "/api/v1/files/4", blockedAt)));

		mockMvc.perform(get("/api/v1/friends/blocks").with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].userId", is(8)))
			.andExpect(jsonPath("$[0].nickname", is("blocked")))
			.andExpect(jsonPath("$[0].profileImageUrl", is("/api/v1/files/4")))
			.andExpect(jsonPath("$[0].blockedAt", is("2026-07-08T08:00:00+09:00")));

		verify(friendService).listBlocks(any(AuthenticatedUser.class));
	}

	@Test
	void listBlockedUserIdsReturnsUserIds() throws Exception {
		when(friendService.listBlockedUserIds(any(AuthenticatedUser.class)))
			.thenReturn(new BlockedUserIdsResponse(List.of(8L, 9L)));

		mockMvc.perform(get("/api/v1/friends/blocked-user-ids").with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.userIds[0]", is(8)))
			.andExpect(jsonPath("$.userIds[1]", is(9)));

		verify(friendService).listBlockedUserIds(any(AuthenticatedUser.class));
	}

	@Test
	void requestFriendReturnsNoContent() throws Exception {
		mockMvc.perform(post("/api/v1/friends/{userId}", 7L).with(authenticated()))
			.andExpect(status().isNoContent());

		verify(friendService).requestFriend(any(AuthenticatedUser.class), eq(7L));
	}

	@Test
	void acceptFriendRequestReturnsNoContent() throws Exception {
		mockMvc.perform(post("/api/v1/friends/{userId}/accept", 7L).with(authenticated()))
			.andExpect(status().isNoContent());

		verify(friendService).acceptFriendRequest(any(AuthenticatedUser.class), eq(7L));
	}

	@Test
	void deleteFriendshipReturnsNoContent() throws Exception {
		mockMvc.perform(delete("/api/v1/friends/{userId}", 7L).with(authenticated()))
			.andExpect(status().isNoContent());

		verify(friendService).deleteFriendship(any(AuthenticatedUser.class), eq(7L));
	}

	@Test
	void blockUserReturnsNoContent() throws Exception {
		mockMvc.perform(post("/api/v1/friends/{userId}/block", 7L).with(authenticated()))
			.andExpect(status().isNoContent());

		verify(friendService).blockUser(any(AuthenticatedUser.class), eq(7L));
	}

	@Test
	void unblockUserReturnsNoContent() throws Exception {
		mockMvc.perform(delete("/api/v1/friends/{userId}/block", 7L).with(authenticated()))
			.andExpect(status().isNoContent());

		verify(friendService).unblockUser(any(AuthenticatedUser.class), eq(7L));
	}

	@Test
	void mapsSelfFriendRequestToBadRequest() throws Exception {
		doThrow(new SelfFriendRequestException())
			.when(friendService).requestFriend(any(AuthenticatedUser.class), eq(42L));

		mockMvc.perform(post("/api/v1/friends/{userId}", 42L).with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("SELF_FRIEND_REQUEST")));
	}

	@Test
	void mapsSelfFriendActionToBadRequest() throws Exception {
		doThrow(new SelfFriendActionException())
			.when(friendService).blockUser(any(AuthenticatedUser.class), eq(42L));

		mockMvc.perform(post("/api/v1/friends/{userId}/block", 42L).with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("INVALID_FRIEND_REQUEST")));
	}

	@Test
	void mapsInvalidDirectionToBadRequest() throws Exception {
		doThrow(new IllegalArgumentException("direction must be received or sent"))
			.when(friendService).listFriendRequests(any(AuthenticatedUser.class), eq("invalid"));

		mockMvc.perform(get("/api/v1/friends/requests")
				.param("direction", "invalid")
				.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
	}

	@Test
	void mapsMissingDirectionToBadRequest() throws Exception {
		doThrow(new IllegalArgumentException("direction must be received or sent"))
			.when(friendService).listFriendRequests(any(AuthenticatedUser.class), isNull());

		mockMvc.perform(get("/api/v1/friends/requests").with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
	}

	@Test
	void mapsBlockedFriendshipToForbidden() throws Exception {
		doThrow(new BlockedFriendshipException())
			.when(friendService).requestFriend(any(AuthenticatedUser.class), eq(7L));

		mockMvc.perform(post("/api/v1/friends/{userId}", 7L).with(authenticated()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("BLOCKED")));
	}

	@Test
	void mapsUserNotFoundToNotFound() throws Exception {
		doThrow(new UserNotFoundException())
			.when(friendService).requestFriend(any(AuthenticatedUser.class), eq(7L));

		mockMvc.perform(post("/api/v1/friends/{userId}", 7L).with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("USER_NOT_FOUND")));
	}

	@Test
	void mapsFriendshipNotFoundToNotFound() throws Exception {
		doThrow(new FriendshipNotFoundException())
			.when(friendService).deleteFriendship(any(AuthenticatedUser.class), eq(7L));

		mockMvc.perform(delete("/api/v1/friends/{userId}", 7L).with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("FRIENDSHIP_NOT_FOUND")));
	}

	@Test
	void mapsFriendRequestExistsToConflict() throws Exception {
		doThrow(new FriendRequestExistsException())
			.when(friendService).requestFriend(any(AuthenticatedUser.class), eq(7L));

		mockMvc.perform(post("/api/v1/friends/{userId}", 7L).with(authenticated()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("REQUEST_EXISTS")));
	}

	@Test
	void mapsAlreadyFriendsToConflict() throws Exception {
		doThrow(new AlreadyFriendsException())
			.when(friendService).requestFriend(any(AuthenticatedUser.class), eq(7L));

		mockMvc.perform(post("/api/v1/friends/{userId}", 7L).with(authenticated()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("ALREADY_FRIENDS")));
	}

	@Test
	void mapsCannotAcceptOwnFriendRequestToConflict() throws Exception {
		doThrow(new CannotAcceptOwnFriendRequestException())
			.when(friendService).acceptFriendRequest(any(AuthenticatedUser.class), eq(7L));

		mockMvc.perform(post("/api/v1/friends/{userId}/accept", 7L).with(authenticated()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("CANNOT_ACCEPT_OWN_REQUEST")));
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
		FriendService friendService() {
			return mock(FriendService.class);
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
