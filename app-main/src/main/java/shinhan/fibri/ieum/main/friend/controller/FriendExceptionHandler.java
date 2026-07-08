package shinhan.fibri.ieum.main.friend.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.friend.exception.AlreadyFriendsException;
import shinhan.fibri.ieum.main.friend.exception.BlockedFriendshipException;
import shinhan.fibri.ieum.main.friend.exception.CannotAcceptOwnFriendRequestException;
import shinhan.fibri.ieum.main.friend.exception.FriendRequestExistsException;
import shinhan.fibri.ieum.main.friend.exception.FriendshipNotFoundException;
import shinhan.fibri.ieum.main.friend.exception.SelfFriendActionException;
import shinhan.fibri.ieum.main.friend.exception.SelfFriendRequestException;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

@RestControllerAdvice(assignableTypes = FriendController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FriendExceptionHandler {

	@ExceptionHandler(SelfFriendRequestException.class)
	public ResponseEntity<AuthErrorResponse> handleSelfFriendRequest(SelfFriendRequestException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("SELF_FRIEND_REQUEST", exception.getMessage()));
	}

	@ExceptionHandler(SelfFriendActionException.class)
	public ResponseEntity<AuthErrorResponse> handleSelfFriendAction(SelfFriendActionException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("INVALID_FRIEND_REQUEST", exception.getMessage()));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<AuthErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("VALIDATION_FAILED", exception.getMessage()));
	}

	@ExceptionHandler(BlockedFriendshipException.class)
	public ResponseEntity<AuthErrorResponse> handleBlockedFriendship(BlockedFriendshipException exception) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(new AuthErrorResponse("BLOCKED", exception.getMessage()));
	}

	@ExceptionHandler(UserNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleUserNotFound(UserNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("USER_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(FriendshipNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleFriendshipNotFound(FriendshipNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("FRIENDSHIP_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(FriendRequestExistsException.class)
	public ResponseEntity<AuthErrorResponse> handleFriendRequestExists(FriendRequestExistsException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("REQUEST_EXISTS", exception.getMessage()));
	}

	@ExceptionHandler(AlreadyFriendsException.class)
	public ResponseEntity<AuthErrorResponse> handleAlreadyFriends(AlreadyFriendsException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("ALREADY_FRIENDS", exception.getMessage()));
	}

	@ExceptionHandler(CannotAcceptOwnFriendRequestException.class)
	public ResponseEntity<AuthErrorResponse> handleCannotAcceptOwnFriendRequest(
		CannotAcceptOwnFriendRequestException exception
	) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("CANNOT_ACCEPT_OWN_REQUEST", exception.getMessage()));
	}
}
