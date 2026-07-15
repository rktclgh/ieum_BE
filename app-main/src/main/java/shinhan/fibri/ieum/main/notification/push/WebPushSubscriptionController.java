package shinhan.fibri.ieum.main.notification.push;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.auth.session.AuthenticatedSessionDetails;

@RestController
@RequestMapping("/api/v1/notifications/push")
@RequiredArgsConstructor
public class WebPushSubscriptionController {

	private final WebPushSubscriptionService service;

	@GetMapping("/config")
	public ResponseEntity<WebPushConfigResponse> config(Authentication authentication) {
		AuthenticatedRequest authenticated = requireAuthenticatedSession(authentication);
		return ResponseEntity.ok(service.config(authenticated.userId(), authenticated.sessionId()));
	}

	@PutMapping("/subscription")
	public ResponseEntity<Void> subscribe(
		Authentication authentication,
		@RequestBody WebPushSubscriptionRequest request
	) {
		AuthenticatedRequest authenticated = requireAuthenticatedSession(authentication);
		service.subscribe(authenticated.userId(), authenticated.sessionId(), request);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/subscription")
	public ResponseEntity<Void> unsubscribe(Authentication authentication) {
		AuthenticatedRequest authenticated = requireAuthenticatedSession(authentication);
		service.unsubscribe(authenticated.sessionId());
		return ResponseEntity.noContent().build();
	}

	private AuthenticatedRequest requireAuthenticatedSession(Authentication authentication) {
		if (authentication == null
				|| !(authentication.getPrincipal() instanceof AuthenticatedUser principal)
				|| !(authentication.getDetails() instanceof AuthenticatedSessionDetails details)) {
			throw new WebPushAuthenticationRequiredException();
		}
		return new AuthenticatedRequest(principal.userId(), details.sessionId());
	}

	private record AuthenticatedRequest(long userId, String sessionId) {

		@Override
		public String toString() {
			return "AuthenticatedRequest[userId=%d, sessionId=<redacted>]".formatted(userId);
		}
	}
}
