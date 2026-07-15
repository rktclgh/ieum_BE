package shinhan.fibri.ieum.main.meeting.controller;

import java.util.Comparator;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.meeting.exception.HostCannotLeaveException;
import shinhan.fibri.ieum.main.meeting.exception.InvalidMeetingRequestException;
import shinhan.fibri.ieum.main.meeting.exception.KickedMemberException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingFullException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingNotFoundException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingNotOpenException;
import shinhan.fibri.ieum.main.meeting.exception.NotHostException;
import shinhan.fibri.ieum.main.meeting.exception.NotMeetingMemberException;
import shinhan.fibri.ieum.main.meeting.exception.ParticipantNotFoundException;
import shinhan.fibri.ieum.main.meeting.exception.ScheduleAlreadyExistsException;
import shinhan.fibri.ieum.main.meeting.exception.ScheduleNotCancellableException;
import shinhan.fibri.ieum.main.meeting.exception.ScheduleNotFoundException;
import shinhan.fibri.ieum.main.meeting.exception.SchedulePermissionDeniedException;

@RestControllerAdvice(assignableTypes = MeetingController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MeetingExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<AuthErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
		List<AuthErrorResponse.FieldError> fieldErrors = exception.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> new AuthErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
			.sorted(Comparator.comparing(AuthErrorResponse.FieldError::field))
			.toList();
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse("VALIDATION_FAILED", "Request validation failed", fieldErrors));
	}

	@ExceptionHandler(InvalidMeetingRequestException.class)
	public ResponseEntity<AuthErrorResponse> handleInvalidMeetingRequest(InvalidMeetingRequestException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(new AuthErrorResponse(
				exception.code(),
				exception.getMessage(),
				List.of(new AuthErrorResponse.FieldError(exception.field(), exception.getMessage()))
			));
	}

	@ExceptionHandler(MeetingNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleMeetingNotFound(MeetingNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("MEETING_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(MeetingNotOpenException.class)
	public ResponseEntity<AuthErrorResponse> handleMeetingNotOpen(MeetingNotOpenException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("MEETING_NOT_OPEN", exception.getMessage()));
	}

	@ExceptionHandler(MeetingFullException.class)
	public ResponseEntity<AuthErrorResponse> handleMeetingFull(MeetingFullException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("MEETING_FULL", exception.getMessage()));
	}

	@ExceptionHandler(KickedMemberException.class)
	public ResponseEntity<AuthErrorResponse> handleKickedMember(KickedMemberException exception) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(new AuthErrorResponse("KICKED_MEMBER", exception.getMessage()));
	}

	@ExceptionHandler(HostCannotLeaveException.class)
	public ResponseEntity<AuthErrorResponse> handleHostCannotLeave(HostCannotLeaveException exception) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(new AuthErrorResponse("HOST_CANNOT_LEAVE", exception.getMessage()));
	}

	@ExceptionHandler(ParticipantNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleParticipantNotFound(ParticipantNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("PARTICIPANT_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(NotHostException.class)
	public ResponseEntity<AuthErrorResponse> handleNotHost(NotHostException exception) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(new AuthErrorResponse("NOT_HOST", exception.getMessage()));
	}

	@ExceptionHandler(NotMeetingMemberException.class)
	public ResponseEntity<AuthErrorResponse> handleNotMeetingMember(NotMeetingMemberException exception) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(new AuthErrorResponse("NOT_MEETING_MEMBER", exception.getMessage()));
	}

	@ExceptionHandler(ScheduleAlreadyExistsException.class)
	public ResponseEntity<AuthErrorResponse> handleScheduleAlreadyExists(ScheduleAlreadyExistsException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("SCHEDULE_ALREADY_EXISTS", exception.getMessage()));
	}

	@ExceptionHandler(ScheduleNotFoundException.class)
	public ResponseEntity<AuthErrorResponse> handleScheduleNotFound(ScheduleNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(new AuthErrorResponse("SCHEDULE_NOT_FOUND", exception.getMessage()));
	}

	@ExceptionHandler(ScheduleNotCancellableException.class)
	public ResponseEntity<AuthErrorResponse> handleScheduleNotCancellable(ScheduleNotCancellableException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new AuthErrorResponse("SCHEDULE_NOT_CANCELLABLE", exception.getMessage()));
	}

	@ExceptionHandler(SchedulePermissionDeniedException.class)
	public ResponseEntity<AuthErrorResponse> handleSchedulePermissionDenied(
		SchedulePermissionDeniedException exception
	) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(new AuthErrorResponse("SCHEDULE_PERMISSION_DENIED", exception.getMessage()));
	}
}
