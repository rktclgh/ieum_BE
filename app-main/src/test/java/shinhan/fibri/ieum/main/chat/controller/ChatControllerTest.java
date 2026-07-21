package shinhan.fibri.ieum.main.chat.controller;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import shinhan.fibri.ieum.common.chat.domain.MessageType;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.chat.dto.ChatCursorPage;
import shinhan.fibri.ieum.main.chat.dto.ChatMessageResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatNoticePageResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatNoticeResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomDetailResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomMemberResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomSummaryResponse;
import shinhan.fibri.ieum.main.chat.exception.BlockedChatException;
import shinhan.fibri.ieum.main.chat.exception.ChatNoticeNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.ChatNoticeSourceNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.ChatRoomNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.GroupLeaveViaMeetingException;
import shinhan.fibri.ieum.main.chat.exception.NotFriendsException;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.chat.exception.SelfChatRoomException;
import shinhan.fibri.ieum.main.chat.service.ChatNoticeRegistrationResult;
import shinhan.fibri.ieum.main.chat.service.ChatNoticeService;
import shinhan.fibri.ieum.main.chat.service.ChatService;
import shinhan.fibri.ieum.main.meeting.exception.NotHostException;
import shinhan.fibri.ieum.main.question.exception.QuestionForbiddenException;
import shinhan.fibri.ieum.main.question.exception.QuestionNotFoundException;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

@WebMvcTest(ChatController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChatControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ChatService chatService;

	@Autowired
	private ChatNoticeService chatNoticeService;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
		reset(chatService);
		reset(chatNoticeService);
	}

	@Test
	void createDirectRoomReturnsRoom() throws Exception {
		when(chatService.createDirectRoom(any(AuthenticatedUser.class), eq(77L)))
			.thenReturn(new ChatRoomResponse(100L, RoomType.direct, null, null, null));

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
	void createQuestionRoomReturnsQuestionRoom() throws Exception {
		when(chatService.createQuestionRoom(any(AuthenticatedUser.class), eq(9L), eq(77L)))
			.thenReturn(new ChatRoomResponse(100L, RoomType.question, null, 9L, "질문 제목"));

		mockMvc.perform(post("/api/v1/chat/rooms/question")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"questionId\":9,\"targetUserId\":77}")
				.with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.roomId", is(100)))
			.andExpect(jsonPath("$.roomType", is("question")))
			.andExpect(jsonPath("$.meetingId").doesNotExist())
			.andExpect(jsonPath("$.questionId", is(9)))
			.andExpect(jsonPath("$.questionTitle", is("질문 제목")));

		verify(chatService).createQuestionRoom(any(AuthenticatedUser.class), eq(9L), eq(77L));
	}

	@Test
	void createQuestionRoomRequiresQuestionId() throws Exception {
		mockMvc.perform(post("/api/v1/chat/rooms/question")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetUserId\":77}")
				.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("questionId")));
	}

	@Test
	void createQuestionRoomRequiresTargetUserId() throws Exception {
		mockMvc.perform(post("/api/v1/chat/rooms/question")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"questionId\":9}")
				.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("targetUserId")));
	}

	@Test
	void createQuestionRoomRequiresPositiveQuestionId() throws Exception {
		mockMvc.perform(post("/api/v1/chat/rooms/question")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"questionId\":0,\"targetUserId\":77}")
				.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("questionId")));
	}

	@Test
	void createQuestionRoomRequiresPositiveTargetUserId() throws Exception {
		mockMvc.perform(post("/api/v1/chat/rooms/question")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"questionId\":9,\"targetUserId\":0}")
				.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("targetUserId")));
	}

	@Test
	void listRoomsReturnsRooms() throws Exception {
		when(chatService.listRooms(any(AuthenticatedUser.class), eq(RoomType.direct)))
			.thenReturn(List.of(new ChatRoomSummaryResponse(
				100L,
				RoomType.direct,
				null,
				null,
				null,
				true,
				true,
				3L,
				new ChatMessageResponse(
					501L,
					100L,
					77L,
					"friend",
					null,
					MessageType.user,
					"hello",
					null,
					OffsetDateTime.parse("2026-07-08T12:00:00+09:00")
				),
				null
			)));

		mockMvc.perform(get("/api/v1/chat/rooms")
				.param("type", "direct")
				.with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].roomId", is(100)))
			.andExpect(jsonPath("$[0].roomType", is("direct")))
			.andExpect(jsonPath("$[0].pinned", is(true)))
			.andExpect(jsonPath("$[0].unreadCount", is(3)))
			.andExpect(jsonPath("$[0].lastMessage.content", is("hello")))
			.andExpect(jsonPath("$[0].lastMessage.messageType", is("user")));
	}

	@Test
	void getRoomReturnsDetail() throws Exception {
		String counterpartProfileImageUrl = "/api/v1/files/123e4567-e89b-12d3-a456-426614174000";
		when(chatService.getRoom(any(AuthenticatedUser.class), eq(100L)))
			.thenReturn(new ChatRoomDetailResponse(
				100L,
				RoomType.direct,
				null,
				null,
				null,
				false,
				true,
				List.of(new ChatRoomMemberResponse(77L, "friend", counterpartProfileImageUrl, "US")),
				new ChatRoomMemberResponse(77L, "friend", counterpartProfileImageUrl, "US")
			));

		mockMvc.perform(get("/api/v1/chat/rooms/{roomId}", 100L).with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.roomId", is(100)))
			.andExpect(jsonPath("$.members[0].userId", is(77)))
			.andExpect(jsonPath("$.members[0].nationality", is("US")))
			.andExpect(jsonPath("$.counterpart.userId", is(77)))
			.andExpect(jsonPath("$.counterpart.profileImageUrl", is(counterpartProfileImageUrl)));
	}

	@Test
	void listMessagesReturnsCursorPage() throws Exception {
		when(chatService.listMessages(any(AuthenticatedUser.class), eq(100L), eq("cursor"), eq(2)))
			.thenReturn(new ChatCursorPage<>(
				List.of(new ChatMessageResponse(
					501L,
					100L,
					77L,
					"friend",
					"/api/v1/files/11111111-1111-1111-1111-111111111111",
					MessageType.user,
					"hello",
					null,
					OffsetDateTime.parse("2026-07-08T12:00:00+09:00")
				)),
				"next"
			));

		mockMvc.perform(get("/api/v1/chat/rooms/{roomId}/messages", 100L)
				.param("cursor", "cursor")
				.param("size", "2")
				.with(authenticated()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].messageId", is(501)))
				.andExpect(jsonPath("$.items[0].senderProfileImageUrl", is("/api/v1/files/11111111-1111-1111-1111-111111111111")))
			.andExpect(jsonPath("$.items[0].messageType", is("user")))
			.andExpect(jsonPath("$.nextCursor", is("next")));
	}

	@Test
	void registerNoticeReturnsCreatedForFirstRegistration() throws Exception {
		ChatNoticeResponse notice = noticeResponse(901L, true);
		when(chatNoticeService.registerNotice(any(AuthenticatedUser.class), eq(100L), eq(501L)))
			.thenReturn(new ChatNoticeRegistrationResult(notice, true));

		mockMvc.perform(post("/api/v1/chat/rooms/{roomId}/notices", 100L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"messageId\":501}")
				.with(authenticated()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.noticeId", is(901)))
			.andExpect(jsonPath("$.message.messageId", is(501)))
			.andExpect(jsonPath("$.message.content", is("notice text")))
			.andExpect(jsonPath("$.pinned", is(true)));

		verify(chatNoticeService).registerNotice(any(AuthenticatedUser.class), eq(100L), eq(501L));
	}

	@Test
	void registerNoticeReturnsOkForDuplicateCanonicalRegistration() throws Exception {
		when(chatNoticeService.registerNotice(any(AuthenticatedUser.class), eq(100L), eq(501L)))
			.thenReturn(new ChatNoticeRegistrationResult(noticeResponse(901L, false), false));

		mockMvc.perform(post("/api/v1/chat/rooms/{roomId}/notices", 100L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"messageId\":501}")
				.with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.noticeId", is(901)));
	}

	@Test
	void registerNoticeValidatesMessageId() throws Exception {
		mockMvc.perform(post("/api/v1/chat/rooms/{roomId}/notices", 100L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}")
				.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("messageId")));
	}

	@Test
	void listNoticesReturnsItemsAndPinnedNotice() throws Exception {
		when(chatNoticeService.listNotices(any(AuthenticatedUser.class), eq(100L), eq("cursor"), eq(2)))
			.thenReturn(new ChatNoticePageResponse(
				List.of(noticeResponse(901L, false)),
				"next",
				noticeResponse(999L, true)
			));

		mockMvc.perform(get("/api/v1/chat/rooms/{roomId}/notices", 100L)
				.param("cursor", "cursor")
				.param("size", "2")
				.with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].noticeId", is(901)))
			.andExpect(jsonPath("$.items[0].pinned", is(false)))
			.andExpect(jsonPath("$.nextCursor", is("next")))
			.andExpect(jsonPath("$.pinnedNotice.noticeId", is(999)))
			.andExpect(jsonPath("$.pinnedNotice.pinned", is(true)));
	}

	@Test
	void pinNoticeReturnsPinnedNotice() throws Exception {
		when(chatNoticeService.pinNotice(any(AuthenticatedUser.class), eq(100L), eq(901L)))
			.thenReturn(noticeResponse(901L, true));

		mockMvc.perform(put("/api/v1/chat/rooms/{roomId}/notices/{noticeId}/pin", 100L, 901L)
				.with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.noticeId", is(901)))
			.andExpect(jsonPath("$.pinned", is(true)));

		verify(chatNoticeService).pinNotice(any(AuthenticatedUser.class), eq(100L), eq(901L));
	}

	@Test
	void unpinNoticeReturnsNoContent() throws Exception {
		mockMvc.perform(delete("/api/v1/chat/rooms/{roomId}/notices/{noticeId}/pin", 100L, 901L)
				.with(authenticated()))
			.andExpect(status().isNoContent());

		verify(chatNoticeService).unpinNotice(any(AuthenticatedUser.class), eq(100L), eq(901L));
	}

	@Test
	void markReadReturnsNoContent() throws Exception {
		mockMvc.perform(post("/api/v1/chat/rooms/{roomId}/read", 100L).with(authenticated()))
			.andExpect(status().isNoContent());

		verify(chatService).markRead(any(AuthenticatedUser.class), eq(100L));
	}

	@Test
	void setPinnedReturnsNoContent() throws Exception {
		mockMvc.perform(put("/api/v1/chat/rooms/{roomId}/pin", 100L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"pinned\":true}")
				.with(authenticated()))
			.andExpect(status().isNoContent());

		verify(chatService).setPinned(any(AuthenticatedUser.class), eq(100L), eq(true));
	}

	@Test
	void setNotifyReturnsNoContent() throws Exception {
		mockMvc.perform(put("/api/v1/chat/rooms/{roomId}/notify", 100L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"enabled\":false}")
				.with(authenticated()))
			.andExpect(status().isNoContent());

		verify(chatService).setNotifyEnabled(any(AuthenticatedUser.class), eq(100L), eq(false));
	}

	@Test
	void leaveRoomReturnsNoContent() throws Exception {
		mockMvc.perform(post("/api/v1/chat/rooms/{roomId}/leave", 100L).with(authenticated()))
			.andExpect(status().isNoContent());

		verify(chatService).leaveRoom(any(AuthenticatedUser.class), eq(100L));
	}

	@Test
	void disbandRoomReturnsNoContent() throws Exception {
		mockMvc.perform(delete("/api/v1/chat/rooms/{roomId}", 100L).with(authenticated()))
			.andExpect(status().isNoContent());

		verify(chatService).disbandRoom(any(AuthenticatedUser.class), eq(100L));
	}

	@Test
	void mapsGroupLeaveViaMeetingToConflict() throws Exception {
		doThrow(new GroupLeaveViaMeetingException())
			.when(chatService).leaveRoom(any(AuthenticatedUser.class), eq(100L));

		mockMvc.perform(post("/api/v1/chat/rooms/{roomId}/leave", 100L).with(authenticated()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("GROUP_LEAVE_VIA_MEETING")));
	}

	@Test
	void setPinnedValidatesPinned() throws Exception {
		mockMvc.perform(put("/api/v1/chat/rooms/{roomId}/pin", 100L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}")
				.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("pinned")));
	}

	@Test
	void setNotifyValidatesEnabled() throws Exception {
		mockMvc.perform(put("/api/v1/chat/rooms/{roomId}/notify", 100L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}")
				.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("enabled")));
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
	void mapsQuestionNotFoundToNotFound() throws Exception {
		doThrow(new QuestionNotFoundException())
			.when(chatService).createQuestionRoom(any(AuthenticatedUser.class), eq(9L), eq(77L));

		mockMvc.perform(post("/api/v1/chat/rooms/question")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"questionId\":9,\"targetUserId\":77}")
				.with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("QUESTION_NOT_FOUND")));
	}

	@Test
	void mapsQuestionForbiddenToForbidden() throws Exception {
		doThrow(new QuestionForbiddenException())
			.when(chatService).createQuestionRoom(any(AuthenticatedUser.class), eq(9L), eq(77L));

		mockMvc.perform(post("/api/v1/chat/rooms/question")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"questionId\":9,\"targetUserId\":77}")
				.with(authenticated()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("FORBIDDEN")));
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

	@Test
	void mapsRoomNotFoundToNotFound() throws Exception {
		when(chatService.getRoom(any(AuthenticatedUser.class), eq(100L)))
			.thenThrow(new ChatRoomNotFoundException());

		mockMvc.perform(get("/api/v1/chat/rooms/{roomId}", 100L).with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("ROOM_NOT_FOUND")));
	}

	@Test
	void mapsNoticeSourceNotFoundToNotFound() throws Exception {
		when(chatNoticeService.registerNotice(any(AuthenticatedUser.class), eq(100L), eq(501L)))
			.thenThrow(new ChatNoticeSourceNotFoundException());

		mockMvc.perform(post("/api/v1/chat/rooms/{roomId}/notices", 100L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"messageId\":501}")
				.with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("MESSAGE_NOT_FOUND")));
	}

	@Test
	void mapsNoticeRoomNotFoundToNotFound() throws Exception {
		when(chatNoticeService.listNotices(any(AuthenticatedUser.class), eq(100L), eq(null), eq(null)))
			.thenThrow(new ChatRoomNotFoundException());

		mockMvc.perform(get("/api/v1/chat/rooms/{roomId}/notices", 100L).with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("ROOM_NOT_FOUND")));
	}

	@Test
	void mapsNoticeNotFoundToNotFound() throws Exception {
		when(chatNoticeService.pinNotice(any(AuthenticatedUser.class), eq(100L), eq(901L)))
			.thenThrow(new ChatNoticeNotFoundException());

		mockMvc.perform(put("/api/v1/chat/rooms/{roomId}/notices/{noticeId}/pin", 100L, 901L)
				.with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("NOTICE_NOT_FOUND")));
	}

	@Test
	void mapsNotRoomMemberToForbidden() throws Exception {
		when(chatService.getRoom(any(AuthenticatedUser.class), eq(100L)))
			.thenThrow(new NotRoomMemberException());

		mockMvc.perform(get("/api/v1/chat/rooms/{roomId}", 100L).with(authenticated()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("NOT_ROOM_MEMBER")));
	}

	@Test
	void mapsNotHostToForbidden() throws Exception {
		doThrow(new NotHostException())
			.when(chatService).disbandRoom(any(AuthenticatedUser.class), eq(100L));

		mockMvc.perform(delete("/api/v1/chat/rooms/{roomId}", 100L).with(authenticated()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("NOT_HOST")));
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

	private ChatNoticeResponse noticeResponse(Long noticeId, boolean pinned) {
		return new ChatNoticeResponse(
			noticeId,
			100L,
			new ChatMessageResponse(
				501L,
				100L,
				77L,
				"sender",
				null,
				MessageType.user,
				"notice text",
				null,
				OffsetDateTime.parse("2026-07-21T10:00:00+09:00")
			),
			42L,
			OffsetDateTime.parse("2026-07-21T11:00:00+09:00"),
			pinned
		);
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
		ChatNoticeService chatNoticeService() {
			return mock(ChatNoticeService.class);
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
