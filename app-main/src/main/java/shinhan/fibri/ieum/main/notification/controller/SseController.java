package shinhan.fibri.ieum.main.notification.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import shinhan.fibri.ieum.main.notification.sse.SseSubscriptionService;

@RestController
@RequestMapping("/api/v1/sse")
@RequiredArgsConstructor
public class SseController {

	private final SseSubscriptionService subscriptionService;

	@GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<SseEmitter> subscribe(
		@CookieValue(value = "access_token", required = false) String accessToken
	) {
		return ResponseEntity.ok()
			.contentType(MediaType.TEXT_EVENT_STREAM)
			.cacheControl(CacheControl.noCache())
			.header("X-Accel-Buffering", "no")
			.body(subscriptionService.subscribe(accessToken));
	}
}
