package shinhan.fibri.ieum.main.answer.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.answer.dto.CreateAnswerRequest;
import shinhan.fibri.ieum.main.answer.dto.CreateAnswerResponse;
import shinhan.fibri.ieum.main.answer.dto.FinalizeAcceptedAnswersRequest;
import shinhan.fibri.ieum.main.answer.dto.FinalizeAcceptedAnswersResponse;
import shinhan.fibri.ieum.main.answer.service.AnswerService;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AnswerController {

	private final AnswerService answerService;

	@PostMapping("/questions/{questionId}/answer")
	public ResponseEntity<CreateAnswerResponse> create(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long questionId,
		@Valid @RequestBody CreateAnswerRequest request
	) {
		CreateAnswerResponse response = answerService.create(principal, questionId, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@PutMapping("/questions/{questionId}/accepted-answers")
	public ResponseEntity<FinalizeAcceptedAnswersResponse> finalizeAcceptedAnswers(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long questionId,
		@Valid @RequestBody FinalizeAcceptedAnswersRequest request
	) {
		FinalizeAcceptedAnswersResponse response = answerService.finalizeSelection(principal, questionId, request);
		return ResponseEntity.ok(response);
	}
}
