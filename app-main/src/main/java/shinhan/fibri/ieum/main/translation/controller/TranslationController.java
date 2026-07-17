package shinhan.fibri.ieum.main.translation.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.translation.dto.TranslationRequest;
import shinhan.fibri.ieum.main.translation.dto.TranslationResponse;
import shinhan.fibri.ieum.main.translation.service.TargetLanguage;
import shinhan.fibri.ieum.main.translation.service.TranslationAuthenticationRequiredException;
import shinhan.fibri.ieum.main.translation.service.TranslationService;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TranslationController {

	private final TranslationService translationService;

	@PostMapping("/translate")
	public ResponseEntity<TranslationResponse> translate(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@Valid @RequestBody TranslationRequest request
	) {
		if (principal == null) {
			throw new TranslationAuthenticationRequiredException();
		}
		return ResponseEntity.ok(
			translationService.translate(principal, request.text(), TargetLanguage.fromCode(request.targetLang()))
		);
	}
}
