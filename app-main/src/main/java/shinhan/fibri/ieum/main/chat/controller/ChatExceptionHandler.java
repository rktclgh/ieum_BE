package shinhan.fibri.ieum.main.chat.controller;

import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.chat.exception.BlockedChatException;
import shinhan.fibri.ieum.main.chat.exception.ChatNoticeNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.ChatNoticeSourceNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.ChatRoomNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.GroupLeaveViaMeetingException;
import shinhan.fibri.ieum.main.chat.exception.NotFriendsException;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.chat.exception.SelfChatRoomException;
import shinhan.fibri.ieum.main.meeting.exception.NotHostException;
import shinhan.fibri.ieum.main.question.exception.QuestionForbiddenException;
import shinhan.fibri.ieum.main.question.exception.QuestionNotFoundException;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

@RestControllerAdvice(assignableTypes = ChatController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ChatExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<AuthErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
		List<AuthErrorResponse.FieldError> fieldErrors = exception.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> new AuthErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
			.toList();
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("VALIDATION_FAILED", "Request validation failed", fieldErrors));
	}

	@ExceptionHandler(SelfChatRoomException.class)
	public ResponseEntity<AuthErrorResponse> handleSelfChatRoom(SelfChatRoomException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("SELF_CHAT_ROOM", exception.getMessage()));
	}

	@ExceptionHandler(NotFriendsException.class)
	public ResponseEntity<AuthErrorResponse> handleNotFriends(NotFriendsException exception) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(new AuthErrorResponse("NOT_FRIENDS", exception.getMessage()));
	}

	@ExceptionHandler(BlockedChatException.class)
	public ResponseEntity<AuthErrorResponse> handleBlockedChat(BlockedChatException exception) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(new AuthErrorResponse("BLOCKED", exception.getMessage()));
	}

	@ExceptionHandler(QuestionForbiddenException.class)
	public ResponseEntity<AuthErrorResponse> handleQuestionForbidden(QuestionForbiddenException exception) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(new AuthErrorResponse("FORBIDDEN", exception.getMessage()));
	}

	@ExceptionHandler(NotRoomMemberException.class)
	public ResponseEntity<AuthErrorResponse> handleNotRoomMember(NotRoomMemberException exception) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(new AuthErrorResponse("NOT_ROOM_MEMBER", exception.getMessage()));
	}

	@ExceptionHandler(GroupLeaveViaMeetingException.class)
	public ResponseEntity<AuthErrorResponse> handleGroupLeaveViaMeeting(GroupLeaveViaMeetingException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("GROUP_LEAVE_VIA_MEETING", exception.getMessage()));
	}

	@ExceptionHandler(UserNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleUserNotFound(UserNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("USER_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(QuestionNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleQuestionNotFound(QuestionNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("QUESTION_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(ChatRoomNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleChatRoomNotFound(ChatRoomNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("ROOM_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(ChatNoticeNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleChatNoticeNotFound(ChatNoticeNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("NOTICE_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(ChatNoticeSourceNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleChatNoticeSourceNotFound(ChatNoticeSourceNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("MESSAGE_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(NotHostException.class)
	public ResponseEntity<AuthErrorResponse> handleNotHost(NotHostException exception) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(new AuthErrorResponse("NOT_HOST", exception.getMessage()));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<AuthErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("VALIDATION_FAILED", exception.getMessage()));
	}
}
