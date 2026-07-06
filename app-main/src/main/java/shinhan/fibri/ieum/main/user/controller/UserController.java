package shinhan.fibri.ieum.main.user.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.user.dto.UpdateUserProfileRequest;
import shinhan.fibri.ieum.main.user.dto.UserMeResponse;
import shinhan.fibri.ieum.main.user.service.UserService;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {

	private final UserService userService;

	@GetMapping
	public ResponseEntity<UserMeResponse> getMe(
		@AuthenticationPrincipal AuthenticatedUser principal
	) {
		return ResponseEntity.ok(userService.getMe(principal));
	}

	@PatchMapping
	public ResponseEntity<UserMeResponse> updateMe(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@Valid @RequestBody UpdateUserProfileRequest request
	) {
		return ResponseEntity.ok(userService.updateMe(principal, request));
	}
}
