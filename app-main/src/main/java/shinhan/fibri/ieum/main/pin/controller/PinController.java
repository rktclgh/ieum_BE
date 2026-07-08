package shinhan.fibri.ieum.main.pin.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.pin.dto.CursorPage;
import shinhan.fibri.ieum.main.pin.dto.PinItem;
import shinhan.fibri.ieum.main.pin.dto.PinListRequest;
import shinhan.fibri.ieum.main.pin.dto.PinMapRequest;
import shinhan.fibri.ieum.main.pin.dto.PinMapResponse;
import shinhan.fibri.ieum.main.pin.service.PinService;

@RestController
@RequestMapping("/api/v1/pins")
@RequiredArgsConstructor
public class PinController {

	private final PinService pinService;

	@GetMapping
	public ResponseEntity<PinMapResponse> getMapPins(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@Valid @ModelAttribute PinMapRequest request
	) {
		return ResponseEntity.ok(pinService.getMapPins(principal, request));
	}

	@GetMapping("/list")
	public ResponseEntity<CursorPage<PinItem>> getListPins(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@Valid @ModelAttribute PinListRequest request
	) {
		return ResponseEntity.ok(pinService.getListPins(principal, request));
	}
}
