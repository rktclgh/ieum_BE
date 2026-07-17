package shinhan.fibri.ieum.main.translation.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.translation.dto.TranslationRequest;
import shinhan.fibri.ieum.main.translation.dto.TranslationResponse;
import shinhan.fibri.ieum.main.translation.service.TargetLanguage;
import shinhan.fibri.ieum.main.translation.service.TranslationService;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TranslationController {

	private final TranslationService translationService;

	@PostMapping("/answers/{answerId}/translation")
	public ResponseEntity<TranslationResponse> translateAnswer(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long answerId,
		@Valid @RequestBody TranslationRequest request
	) {
		return ResponseEntity.ok(
			translationService.translateAnswer(principal, answerId, TargetLanguage.fromCode(request.targetLang()))
		);
	}

	@PostMapping("/chat/messages/{messageId}/translation")
	public ResponseEntity<TranslationResponse> translateChatMessage(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long messageId,
		@Valid @RequestBody TranslationRequest request
	) {
		return ResponseEntity.ok(
			translationService.translateChatMessage(principal, messageId, TargetLanguage.fromCode(request.targetLang()))
		);
	}
}
