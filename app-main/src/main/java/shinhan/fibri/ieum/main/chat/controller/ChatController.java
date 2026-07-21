package shinhan.fibri.ieum.main.chat.controller;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.chat.domain.RoomType;
import shinhan.fibri.ieum.main.chat.dto.ChatCursorPage;
import shinhan.fibri.ieum.main.chat.dto.ChatMessageResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatNoticePageResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatNoticeRequest;
import shinhan.fibri.ieum.main.chat.dto.ChatNoticeResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomDetailResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomSummaryResponse;
import shinhan.fibri.ieum.main.chat.dto.DirectRoomRequest;
import shinhan.fibri.ieum.main.chat.dto.NotifyRoomRequest;
import shinhan.fibri.ieum.main.chat.dto.PinRoomRequest;
import shinhan.fibri.ieum.main.chat.dto.QuestionRoomRequest;
import shinhan.fibri.ieum.main.chat.service.ChatNoticeRegistrationResult;
import shinhan.fibri.ieum.main.chat.service.ChatNoticeService;
import shinhan.fibri.ieum.main.chat.service.ChatService;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

	private final ChatService chatService;
	private final ChatNoticeService chatNoticeService;

	@GetMapping("/rooms")
	public ResponseEntity<List<ChatRoomSummaryResponse>> listRooms(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@RequestParam(required = false) RoomType type
	) {
		return ResponseEntity.ok(chatService.listRooms(principal, type));
	}

	@PostMapping("/rooms/direct")
	public ResponseEntity<ChatRoomResponse> createDirectRoom(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@Valid @RequestBody DirectRoomRequest request
	) {
		return ResponseEntity.ok(chatService.createDirectRoom(principal, request.friendId()));
	}

	@PostMapping("/rooms/question")
	public ResponseEntity<ChatRoomResponse> createQuestionRoom(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@Valid @RequestBody QuestionRoomRequest request
	) {
		return ResponseEntity.ok(
			chatService.createQuestionRoom(principal, request.questionId(), request.targetUserId())
		);
	}

	@GetMapping("/rooms/{roomId}")
	public ResponseEntity<ChatRoomDetailResponse> getRoom(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long roomId
	) {
		return ResponseEntity.ok(chatService.getRoom(principal, roomId));
	}

	@GetMapping("/rooms/{roomId}/messages")
	public ResponseEntity<ChatCursorPage<ChatMessageResponse>> listMessages(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long roomId,
		@RequestParam(required = false) String cursor,
		@RequestParam(required = false) Integer size
	) {
		return ResponseEntity.ok(chatService.listMessages(principal, roomId, cursor, size));
	}

	@PostMapping("/rooms/{roomId}/notices")
	public ResponseEntity<ChatNoticeResponse> registerNotice(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long roomId,
		@Valid @RequestBody ChatNoticeRequest request
	) {
		ChatNoticeRegistrationResult result = chatNoticeService.registerNotice(principal, roomId, request.messageId());
		if (!result.created()) {
			return ResponseEntity.ok(result.notice());
		}
		return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
				.path("/{noticeId}")
				.buildAndExpand(result.notice().noticeId())
				.toUri())
			.body(result.notice());
	}

	@GetMapping("/rooms/{roomId}/notices")
	public ResponseEntity<ChatNoticePageResponse> listNotices(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long roomId,
		@RequestParam(required = false) String cursor,
		@RequestParam(required = false) Integer size
	) {
		return ResponseEntity.ok(chatNoticeService.listNotices(principal, roomId, cursor, size));
	}

	@PutMapping("/rooms/{roomId}/notices/{noticeId}/pin")
	public ResponseEntity<ChatNoticeResponse> pinNotice(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long roomId,
		@PathVariable Long noticeId
	) {
		return ResponseEntity.ok(chatNoticeService.pinNotice(principal, roomId, noticeId));
	}

	@DeleteMapping("/rooms/{roomId}/notices/{noticeId}/pin")
	public ResponseEntity<Void> unpinNotice(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long roomId,
		@PathVariable Long noticeId
	) {
		chatNoticeService.unpinNotice(principal, roomId, noticeId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/rooms/{roomId}/read")
	public ResponseEntity<Void> markRead(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long roomId
	) {
		chatService.markRead(principal, roomId);
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/rooms/{roomId}/pin")
	public ResponseEntity<Void> setPinned(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long roomId,
		@Valid @RequestBody PinRoomRequest request
	) {
		chatService.setPinned(principal, roomId, request.pinned());
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/rooms/{roomId}/notify")
	public ResponseEntity<Void> setNotify(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long roomId,
		@Valid @RequestBody NotifyRoomRequest request
	) {
		chatService.setNotifyEnabled(principal, roomId, request.enabled());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/rooms/{roomId}/leave")
	public ResponseEntity<Void> leaveRoom(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long roomId
	) {
		chatService.leaveRoom(principal, roomId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/rooms/{roomId}")
	public ResponseEntity<Void> disbandRoom(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long roomId
	) {
		chatService.disbandRoom(principal, roomId);
		return ResponseEntity.noContent().build();
	}
}
