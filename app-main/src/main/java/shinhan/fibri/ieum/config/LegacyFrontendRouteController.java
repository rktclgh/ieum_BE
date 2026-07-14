package shinhan.fibri.ieum.config;

import java.net.URI;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public class LegacyFrontendRouteController {

	private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");

	@GetMapping("/chats/{chatId:[1-9][0-9]*}")
	public ResponseEntity<Void> redirectChatRoom(@PathVariable String chatId) {
		return redirectWithId("/chats/room/", "chatId", chatId);
	}

	@GetMapping("/chats/{chatId:[1-9][0-9]*}/notices")
	public ResponseEntity<Void> redirectChatNotices(@PathVariable String chatId) {
		return redirectWithId("/chats/notices/", "chatId", chatId);
	}

	@GetMapping("/chats/{chatId:[1-9][0-9]*}/schedule")
	public ResponseEntity<Void> redirectChatSchedule(@PathVariable String chatId) {
		return redirectWithId("/chats/schedule/", "chatId", chatId);
	}

	@GetMapping("/chats/{chatId:[1-9][0-9]*}/report")
	public ResponseEntity<Void> redirectChatReport(
		@PathVariable String chatId,
		@RequestParam(required = false) String messageId,
		@RequestParam(required = false) String target
	) {
		Long parsedChatId = parsePositiveLong(chatId);
		Long parsedMessageId = parsePositiveLong(messageId);
		if (parsedChatId == null || parsedMessageId == null) {
			return ResponseEntity.notFound().build();
		}

		UriComponentsBuilder locationBuilder = UriComponentsBuilder.fromPath("/chats/report/")
			.queryParam("chatId", parsedChatId)
			.queryParam("messageId", parsedMessageId);
		if (target != null) {
			locationBuilder.queryParam("target", target);
		}
		return temporaryRedirect(locationBuilder.build().encode().toUri());
	}

	@GetMapping("/meetups/{meetingId:[1-9][0-9]*}")
	public ResponseEntity<Void> redirectMeetupDetail(@PathVariable String meetingId) {
		return redirectWithId("/meetups/detail/", "meetingId", meetingId);
	}

	@GetMapping("/questions/{questionId:[1-9][0-9]*}")
	public ResponseEntity<Void> redirectQuestionDetail(@PathVariable String questionId) {
		return redirectWithId("/questions/detail/", "questionId", questionId);
	}

	private ResponseEntity<Void> redirectWithId(String path, String parameterName, String rawId) {
		Long id = parsePositiveLong(rawId);
		if (id == null) {
			return ResponseEntity.notFound().build();
		}
		URI location = UriComponentsBuilder.fromPath(path)
			.queryParam(parameterName, id)
			.build()
			.encode()
			.toUri();
		return temporaryRedirect(location);
	}

	private static Long parsePositiveLong(String value) {
		if (value == null || !POSITIVE_DECIMAL.matcher(value).matches()) {
			return null;
		}
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private static ResponseEntity<Void> temporaryRedirect(URI location) {
		return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
	}
}
