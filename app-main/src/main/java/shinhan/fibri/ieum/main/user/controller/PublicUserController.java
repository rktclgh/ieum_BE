package shinhan.fibri.ieum.main.user.controller;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.user.dto.PublicUserProfileResponse;
import shinhan.fibri.ieum.main.user.dto.UserSearchResponse;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;
import shinhan.fibri.ieum.main.user.service.UserService;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class PublicUserController {

	private final UserService userService;

	@GetMapping("/search")
	public ResponseEntity<List<UserSearchResponse>> searchUsers(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@RequestParam(required = false) String nickname
	) {
		return ResponseEntity.ok(userService.searchUsers(principal, nickname));
	}

	@GetMapping("/{userId}")
	public ResponseEntity<PublicUserProfileResponse> getPublicProfile(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long userId
	) {
		return ResponseEntity.ok(userService.getPublicProfile(principal, userId));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<AuthErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("VALIDATION_FAILED", exception.getMessage()));
	}

	@ExceptionHandler(UserNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleUserNotFound(UserNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("USER_NOT_FOUND", exception.getMessage()));
	}
}
