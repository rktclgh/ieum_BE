package shinhan.fibri.ieum.main.chat.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomResponse;
import shinhan.fibri.ieum.main.chat.dto.DirectRoomRequest;
import shinhan.fibri.ieum.main.chat.service.ChatService;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

	private final ChatService chatService;

	@PostMapping("/rooms/direct")
	public ResponseEntity<ChatRoomResponse> createDirectRoom(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@Valid @RequestBody DirectRoomRequest request
	) {
		return ResponseEntity.ok(chatService.createDirectRoom(principal, request.friendId()));
	}
}
