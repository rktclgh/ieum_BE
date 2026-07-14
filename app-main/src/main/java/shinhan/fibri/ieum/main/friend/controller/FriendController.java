package shinhan.fibri.ieum.main.friend.controller;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.friend.dto.BlockedUserIdsResponse;
import shinhan.fibri.ieum.main.friend.dto.BlockedUserResponse;
import shinhan.fibri.ieum.main.friend.dto.FriendRequestResponse;
import shinhan.fibri.ieum.main.friend.dto.FriendResponse;
import shinhan.fibri.ieum.main.friend.service.FriendService;

@RestController
@RequestMapping("/api/v1/friends")
@RequiredArgsConstructor
public class FriendController {

	private final FriendService friendService;

	@GetMapping
	public ResponseEntity<List<FriendResponse>> listFriends(
		@AuthenticationPrincipal AuthenticatedUser principal
	) {
		return ResponseEntity.ok(friendService.listFriends(principal));
	}

	@GetMapping("/requests")
	public ResponseEntity<List<FriendRequestResponse>> listFriendRequests(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@RequestParam(required = false) String direction
	) {
		return ResponseEntity.ok(friendService.listFriendRequests(principal, direction));
	}

	@GetMapping("/blocks")
	public ResponseEntity<List<BlockedUserResponse>> listBlocks(
		@AuthenticationPrincipal AuthenticatedUser principal
	) {
		return ResponseEntity.ok(friendService.listBlocks(principal));
	}

	@GetMapping("/blocked-user-ids")
	public ResponseEntity<BlockedUserIdsResponse> listBlockedUserIds(
		@AuthenticationPrincipal AuthenticatedUser principal
	) {
		return ResponseEntity.ok(friendService.listBlockedUserIds(principal));
	}

	@PostMapping("/{userId}")
	public ResponseEntity<Void> requestFriend(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long userId
	) {
		friendService.requestFriend(principal, userId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{userId}/accept")
	public ResponseEntity<Void> acceptFriendRequest(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long userId
	) {
		friendService.acceptFriendRequest(principal, userId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{userId}")
	public ResponseEntity<Void> deleteFriendship(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long userId
	) {
		friendService.deleteFriendship(principal, userId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{userId}/block")
	public ResponseEntity<Void> blockUser(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long userId
	) {
		friendService.blockUser(principal, userId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{userId}/block")
	public ResponseEntity<Void> unblockUser(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long userId
	) {
		friendService.unblockUser(principal, userId);
		return ResponseEntity.noContent().build();
	}
}
