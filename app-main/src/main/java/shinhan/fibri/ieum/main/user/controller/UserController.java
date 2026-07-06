package shinhan.fibri.ieum.main.user.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.auth.session.AuthCookieWriter;
import shinhan.fibri.ieum.main.user.dto.UpdateUserLocationRequest;
import shinhan.fibri.ieum.main.user.dto.UpdateUserProfileRequest;
import shinhan.fibri.ieum.main.user.dto.UpdateUserSettingsRequest;
import shinhan.fibri.ieum.main.user.dto.UserMeResponse;
import shinhan.fibri.ieum.main.user.dto.UserSettingsResponse;
import shinhan.fibri.ieum.main.user.service.UserService;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {

	private final UserService userService;
	private final AuthCookieWriter authCookieWriter;

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

	@PatchMapping("/settings")
	public ResponseEntity<UserSettingsResponse> updateSettings(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@Valid @RequestBody UpdateUserSettingsRequest request
	) {
		return ResponseEntity.ok(userService.updateSettings(principal, request));
	}

	@PutMapping("/location")
	public ResponseEntity<Void> updateLocation(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@Valid @RequestBody UpdateUserLocationRequest request
	) {
		userService.updateLocation(principal, request);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping
	public ResponseEntity<Void> withdraw(
		@AuthenticationPrincipal AuthenticatedUser principal,
		HttpServletResponse response
	) {
		userService.withdraw(principal);
		authCookieWriter.writeExpiredAuthCookies(response);
		return ResponseEntity.noContent().build();
	}
}
