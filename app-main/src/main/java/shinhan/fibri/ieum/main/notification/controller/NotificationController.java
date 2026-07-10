package shinhan.fibri.ieum.main.notification.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.notification.dto.NotificationListResponse;
import shinhan.fibri.ieum.main.notification.dto.NotificationReadAllResponse;
import shinhan.fibri.ieum.main.notification.service.NotificationService;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

	private final NotificationService notificationService;

	@GetMapping
	public ResponseEntity<NotificationListResponse> list(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@RequestParam(required = false) String cursor,
		@RequestParam(required = false) Integer size
	) {
		return ResponseEntity.ok(notificationService.list(principal.userId(), cursor, size));
	}

	@PostMapping("/{notificationId}/read")
	public ResponseEntity<Void> markRead(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long notificationId
	) {
		notificationService.markRead(principal.userId(), notificationId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/read-all")
	public ResponseEntity<NotificationReadAllResponse> markAllRead(
		@AuthenticationPrincipal AuthenticatedUser principal
	) {
		return ResponseEntity.ok(notificationService.markAllRead(principal.userId()));
	}

	@DeleteMapping("/{notificationId}")
	public ResponseEntity<Void> delete(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long notificationId
	) {
		notificationService.delete(principal.userId(), notificationId);
		return ResponseEntity.noContent().build();
	}
}
